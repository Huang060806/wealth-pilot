package com.wealthpilot.service;

import com.wealthpilot.tools.PlannerTools;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 核心：GLM + 对话记忆 + 工具调用
 */
@Service
public class AgentService {

    public interface Assistant {
        @SystemMessage("""
                你是 WealthPilot，一位专业的资产规划师 Agent。工作分四步：
                1. 用友好的对话收集用户资产信息：现金、定期存款、股票、基金、房产、负债、月收入、月支出、年龄、财务目标、规划年限。
                   每收集到一批信息就调用 recordAssets 工具记录。不要一次问太多问题，像真人顾问一样循序渐进。
                   资产单位是万元，收支单位是元/月。
                   严格区分：用户说"现金/活期的钱"→ cash；明确说"定期存款"→ deposit；只说"存款"不区分时优先记 cash。
                   用户说"基金"→ funds，"股票"→ stocks，不要一个值同时填进多个字段。
                   没提到的字段就不要传，绝对不要传 0 或空字符串。goal 是文字描述（如"买房首付"），不是数字。
                2. 信息足够后（至少有主要资产、收支、年龄、目标），询问用户的风险承受能力：
                   能接受多大亏损？亏 10% 以内→conservative；能接受 20-30% 波动→balanced；追求高收益能承受大跌→aggressive。
                   确认后调用 setRiskLevel。
                3. 用户确认信息无误并要求出方案时，调用 generatePlan。
                4. 把规划结果用通俗的中文解释给用户，包括配置建议、三条收益曲线含义、蒙特卡洛区间含义，
                   提醒用户页面右侧可以查看图表。结尾必须提醒"本规划仅供参考，不构成投资建议"。
                注意：永远不要自己编造收益数字，所有数字必须来自工具返回。没收集到信息就老老实实问。
                """)
        String chat(@MemoryId String sessionId, @UserMessage String message);
    }

    private final Assistant assistant;

    public AgentService(@Value("${glm.base-url}") String baseUrl,
                        @Value("${glm.api-key}") String apiKey,
                        @Value("${glm.model}") String model,
                        PlannerTools tools) {
        ChatLanguageModel chatModel = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(0.3)
                .logRequests(true)
                .logResponses(true)
                .build();

        Map<String, ChatMemory> memories = new ConcurrentHashMap<>();
        this.assistant = AiServices.builder(Assistant.class)
                .chatLanguageModel(chatModel)
                .chatMemoryProvider(sessionId -> memories.computeIfAbsent(
                        (String) sessionId, k -> MessageWindowChatMemory.withMaxMessages(30)))
                .tools(tools)
                .build();
    }

    public String chat(String sessionId, String message) {
        return assistant.chat(sessionId, message);
    }
}
