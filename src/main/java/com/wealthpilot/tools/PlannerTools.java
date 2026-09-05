package com.wealthpilot.tools;

import com.wealthpilot.model.AssetProfile;
import com.wealthpilot.model.PlanResult;
import com.wealthpilot.service.FinanceService;
import com.wealthpilot.service.ProfileStore;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Agent 可调用的工具集：LLM 负责从对话里抽取参数，计算全部由确定性代码完成。
 * 所有数值参数用 String 接收、内部容错解析（小模型会漏传或传空串，避免框架层 NPE）。
 */
@Component
@RequiredArgsConstructor
public class PlannerTools {

    private final ProfileStore store;
    private final FinanceService financeService;

    @Tool("记录用户的资产信息。金额单位：资产类为万元，收支类为元/月。只传用户本次明确提到的字段，没提到的字段不要传。")
    public String recordAssets(
            @ToolMemoryId String sessionId,
            String cash, String deposit, String stocks, String funds, String realEstate,
            String debts, String monthlyIncome, String monthlyExpense, String age,
            String goal, String years) {
        AssetProfile p = store.profileOf(sessionId);
        Double v;
        if ((v = num(cash)) != null) p.setCash(v);
        if ((v = num(deposit)) != null) p.setDeposit(v);
        if ((v = num(stocks)) != null) p.setStocks(v);
        if ((v = num(funds)) != null) p.setFunds(v);
        if ((v = num(realEstate)) != null) p.setRealEstate(v);
        if ((v = num(debts)) != null) p.setDebts(v);
        if ((v = num(monthlyIncome)) != null) p.setMonthlyIncome(v);
        if ((v = num(monthlyExpense)) != null) p.setMonthlyExpense(v);
        if ((v = num(age)) != null) p.setAge(v.intValue());
        if ((v = num(years)) != null && v > 0) p.setYears(v.intValue());
        if (goal != null && !goal.isBlank()) p.setGoal(goal.trim());
        return "已记录。" + p.summary();
    }

    @Tool("设定用户的风险偏好。level 只能是 conservative(保守)/balanced(稳健)/aggressive(激进)。")
    public String setRiskLevel(@ToolMemoryId String sessionId, String level) {
        String normalized = switch (level == null ? "" : level.toLowerCase()) {
            case "conservative", "保守" -> "conservative";
            case "aggressive", "激进" -> "aggressive";
            default -> "balanced";
        };
        store.profileOf(sessionId).setRiskLevel(normalized);
        return "风险偏好已设定为 " + normalized;
    }

    @Tool("当资产信息和风险偏好都收集完成、用户明确要看规划结果时，生成未来收益规划。返回计算摘要。")
    public String generatePlan(@ToolMemoryId String sessionId) {
        AssetProfile p = store.profileOf(sessionId);
        PlanResult plan = financeService.generatePlan(p);
        store.savePlan(sessionId, plan);
        return String.format(
                "规划已生成。当前净资产 %.2f 万元，月结余 %.0f 元，建议配置 %s。" +
                "%d 年后：悲观 %.2f 万 / 中性 %.2f 万 / 乐观 %.2f 万。" +
                "蒙特卡洛 %d 次模拟：中位数 %.2f 万，10%%分位 %.2f 万，90%%分位 %.2f 万。" +
                "（请告知用户可在页面查看完整曲线图）",
                plan.getNetWorth(), plan.getMonthlySaving(), plan.getAllocation(),
                p.getYears() != null ? p.getYears() : 10,
                plan.getPessimistic().get(plan.getPessimistic().size() - 1),
                plan.getExpected().get(plan.getExpected().size() - 1),
                plan.getOptimistic().get(plan.getOptimistic().size() - 1),
                plan.getMcRuns(), plan.getMcMedian(), plan.getMcP10(), plan.getMcP90());
    }

    /** 容错数字解析：null/空串/非数字 → null（跳过该字段） */
    private Double num(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Double.parseDouble(s.replaceAll("[^0-9.\\-]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
