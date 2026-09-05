package com.wealthpilot.model;

import lombok.Data;

/**
 * 用户资产档案（会话内累积，Agent 多轮对话逐步填充）
 */
@Data
public class AssetProfile {
    // 资产（单位：万元）
    private Double cash;          // 现金/活期
    private Double deposit;       // 定期存款
    private Double stocks;        // 股票
    private Double funds;         // 基金
    private Double realEstate;    // 房产估值
    private Double debts;         // 负债
    // 收支（单位：元/月）
    private Double monthlyIncome;
    private Double monthlyExpense;
    // 画像
    private Integer age;
    private String goal;          // 财务目标（买房/退休/教育等）
    private Integer years;        // 规划年限
    private String riskLevel;     // conservative / balanced / aggressive

    public double totalAssets() {
        return nz(cash) + nz(deposit) + nz(stocks) + nz(funds) + nz(realEstate);
    }

    public double netWorth() {
        return totalAssets() - nz(debts);
    }

    private double nz(Double v) { return v == null ? 0 : v; }

    /** 已收集到的信息摘要，供 Agent 判断还缺什么 */
    public String summary() {
        StringBuilder sb = new StringBuilder("当前档案: ");
        if (cash != null) sb.append("现金").append(cash).append("万 ");
        if (deposit != null) sb.append("定存").append(deposit).append("万 ");
        if (stocks != null) sb.append("股票").append(stocks).append("万 ");
        if (funds != null) sb.append("基金").append(funds).append("万 ");
        if (realEstate != null) sb.append("房产").append(realEstate).append("万 ");
        if (debts != null) sb.append("负债").append(debts).append("万 ");
        if (monthlyIncome != null) sb.append("月收入").append(monthlyIncome).append("元 ");
        if (monthlyExpense != null) sb.append("月支出").append(monthlyExpense).append("元 ");
        if (age != null) sb.append("年龄").append(age).append("岁 ");
        if (goal != null) sb.append("目标:").append(goal).append(" ");
        if (years != null) sb.append("规划").append(years).append("年 ");
        if (riskLevel != null) sb.append("风险偏好:").append(riskLevel);
        return sb.toString();
    }
}
