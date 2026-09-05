package com.wealthpilot.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 规划结果：配置建议 + 三情景曲线 + 蒙特卡洛置信区间
 */
@Data
@Builder
public class PlanResult {
    private double netWorth;                 // 当前净资产（万元）
    private double monthlySaving;            // 月结余（元）
    private Map<String, Double> allocation;  // 建议配置比例 {股票:0.6, 债券:0.3, 现金:0.1}
    private String riskLevel;

    private List<Double> years;              // [0..N]
    private List<Double> pessimistic;        // 悲观曲线（净资产，万元）
    private List<Double> expected;           // 中性曲线
    private List<Double> optimistic;         // 乐观曲线

    private double mcMedian;                 // 蒙特卡洛终值中位数
    private double mcP10;                    // 10% 分位（差）
    private double mcP90;                    // 90% 分位（好）
    private int mcRuns;                      // 模拟次数
}
