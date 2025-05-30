package com.tencent.supersonic.chat.server.parser;

import com.tencent.supersonic.chat.api.pojo.response.QueryResp;
import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.chat.server.pojo.ParseContext;
import com.tencent.supersonic.chat.server.service.AgentService;
import com.tencent.supersonic.chat.server.service.ChatManageService;
import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.enums.AppModule;
import com.tencent.supersonic.common.util.ChatAppManager;
import com.tencent.supersonic.headless.api.pojo.response.QueryState;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.provider.ModelProvider;
import lombok.extern.slf4j.Slf4j;
import dev.langchain4j.model.chat.ChatLanguageModel;
import com.tencent.supersonic.common.util.ContextUtils;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class LLMBasedParserSelector {
    private final List<ChatQueryParser> availableParsers;
    private final Map<String, ChatQueryParser> nameToParserMap;
    public static final String APP_KEY = "S2SQL_PARSER";
    public static final String PROMPT_TEMPLATE =
                "你是一个语义理解助手，负责根据用户的自然语言问题选择最合适的解析器。\n" +
                        "以下是可用的解析器列表：\n" +
                        "{{parsers}}\n\n" +
                        "当前问题是：{{question}}\n\n" +
                        "历史问题是：{{history}}\n\n"+
                        "规则是：1、不考虑PlainTextParser,直接过滤掉这个解析器。\n"+
                        "2、数据库交互优先原则：\n当用户问题包含明确的数据库操作意图（如\"查询/统计/筛选[实体]\"）、涉及结构化数据操作（如\"按...分组/排序\"）、或提及具体表/字段时，强制选择 NL2SQL解析器 。例如：\"2023年销售额超过100万的客户有哪些？\"\n" +
                        "3、功能调用识别机制：\n" +
                        "当用户问题包含动作性动词（如\"发送/生成/执行...\"）或需要外部系统交互（如API调用、文件操作、查询当前日期、查询当前地点等），则触发 NL2Plugin解析器。例如：\"查询当前日期是什么时候\"。\n"+
                        "4、业务知识识别机制：\n"+
                        "当用户问题包含比较专业的知识询问时（如专业知识询问、业务口径询问）或数据schema询问（如表结构询问、数据结构询问、数据集结构等），则触发 NL2Plugin解析器。例如：\"全产品库存周转的口径是什么\"。"+
                        "5、歧义处理策略\n" +
                        "当意图模糊时，默认使用NL2SQL解析器。\n" +
                        "6、当用户指定解析器时，强制使用用户指定的解析器。\n"+
                        "7、根据历史问题，判断当前问题是否是多轮对话改写问题，如果是，强制使用NL2SQL解析器。\n"+
                        "请从上述解析器中选择最合适的一个，并只返回解析器名称（例如：NL2SQLParser）。\n" +
                        "你的回答必须严格符合格式，不要添加任何额外信息。\n";

    public LLMBasedParserSelector(List<ChatQueryParser> parsers) {
        this.availableParsers = parsers;
        this.nameToParserMap = new HashMap<>();
        for (ChatQueryParser parser : parsers) {
            String name = parser.getClass().getSimpleName();
            nameToParserMap.put(name,parser);
        };
        ChatAppManager.register(APP_KEY, ChatApp.builder().prompt(PROMPT_TEMPLATE).name("parser选择器")
                .appModule(AppModule.CHAT).description("根据用户输入的问题选择使用那个parser").enable(false).build());
    }

    public void choose(ParseContext parseContext) {

        String question = parseContext.getRequest().getQueryText();
        List<String> parserNames = availableParsers.stream()
                .map(p -> p.getClass().getSimpleName())
                .collect(Collectors.toList());
        List<QueryResp> historyQueries =
                getHistoryQueries(parseContext.getRequest().getChatId(), 1);
        String historyQuestions = null;
        if(historyQueries.isEmpty()){
            historyQuestions = "";
        }else {
            historyQuestions = historyQueries.get(0).getQueryText();
        }
        String promptContent = generatePrompt(question, parserNames,historyQuestions);

        try {
            AgentService agentService = ContextUtils.getBean(AgentService.class);
            Agent chatAgent = agentService.getAgent(parseContext.getAgent().getId());
            ChatApp chatApp = chatAgent.getChatAppConfig().get(APP_KEY);
            if (Objects.isNull(chatApp) || !chatApp.isEnable()) {
                return ;
            }
            ChatLanguageModel model = ModelProvider.getChatModel(chatApp.getChatModelConfig());
            Prompt prompt = PromptTemplate.from(promptContent).apply(Collections.EMPTY_MAP);
            Response<AiMessage> response = model.generate(prompt.toUserMessage());
            String selectedParserName = response.content().text().trim();
            log.info("LLM selected parser: {}", selectedParserName);
            ChatQueryParser selectedParser = nameToParserMap.get(selectedParserName);
            if (selectedParser != null && selectedParser.accept(parseContext)) {
                selectedParser.parse(parseContext);
            } else {
                log.warn("Selected parser not found or not applicable: {}", selectedParserName);
                fallbackToDefault(parseContext);
            }

        } catch (Exception e) {
            log.error("Failed to use LLM to select parser", e);
            fallbackToDefault(parseContext);
        }
    }
    private void fallbackToDefault(ParseContext parseContext) {
        for (ChatQueryParser parser : availableParsers) {
            if (parser.accept(parseContext)) {
                parser.parse(parseContext);
                break;
            }
        }
    }

    private String generatePrompt(String question, List<String> parserNames,String historyquestions) {
        String parsersStr = String.join(", ", parserNames);
        return PROMPT_TEMPLATE
                .replace("{{parsers}}", parsersStr)
                .replace("{{question}}", question)
                .replace("{{history}}", historyquestions);
    }
    private List<QueryResp> getHistoryQueries(int chatId, int multiNum) {
        ChatManageService chatManageService = ContextUtils.getBean(ChatManageService.class);
        List<QueryResp> contextualParseInfoList = chatManageService.getChatQueries(chatId).stream()
                .filter(q -> Objects.nonNull(q.getQueryResult())
                        && q.getQueryResult().getQueryState() == QueryState.SUCCESS)
                .collect(Collectors.toList());

        List<QueryResp> contextualList = contextualParseInfoList.subList(0,
                Math.min(multiNum, contextualParseInfoList.size()));
        Collections.reverse(contextualList);
        return contextualList;
    }
}
