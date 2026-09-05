package com.wealthpilot.service;

import com.wealthpilot.model.AssetProfile;
import com.wealthpilot.model.PlanResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 确定性金融计算引擎：所有数字都在这里算，LLM 只负责理解和表达
 */
@Service
public class FinanceService {

    private final Random random = new Random();

    /** 各资产类别的年化假设：{期望收益率, 波动率} */
    private static final Map<String, double[]> ASSET_PARAMS = Map.of(
            "股票", new double[]{0.07, 0.20},
            "债券", new double[]{0.03, 0.05},
            "现金", new double[]{0.015, 0.005}
    );

    /** 风险偏好 → 配置比例 */
    private static final Map<String, Map<String, Double>> ALLOCATIONS = Map.of(
            "conservative", Map.of("股票", 0.2, "债券", 0.5, "现金", 0.3),
            "balanced",     Map.of("股票", 0.5, "债券", 0.3, "现金", 0.2),
            "aggressive",   Map.of("股票", 0.75, "债券", 0.15, "现金", 0.10)
    );

    public Map<String, Double> allocationFor(String riskLevel) {
        return ALLOCATIONS.getOrDefault(riskLevel, ALLOCATIONS.get("balanced"));
    }

    /** 组合期望收益率 / 波动率（简化：波动率按权重线性，忽略相关性） */
    private double[] portfolioParams(Map<String, Double> alloc) {
        double ret = 0, vol = 0;
        for (Map.Entry<String, Double> e : alloc.entrySet()) {
            double[] p = ASSET_PARAMS.get(e.getKey());
            ret += p[0] * e.getValue();
            vol += p[1] * e.getValue();
        }
        return new double[]{ret, vol};
    }

    /**
     * 生成完整规划：三情景确定性曲线 + 蒙特卡洛模拟
     * 逻辑：当前可投资资产 + 每月结余定投，按配置比例复利增长
     */
    public PlanResult generatePlan(AssetProfile profile) {
        int years = profile.getYears() != null ? profile.getYears() : 10;
        Map<String, Double> alloc = allocationFor(profile.getRiskLevel());
        double[] params = portfolioParams(alloc);
        double expectedRet = params[0];
        double vol = params[1];

        // 可投资资产 = 净资产（房产自住的简化处理：全部纳入，可在报告中说明）
        double investable = profile.netWorth();
        double monthlySaving = (profile.getMonthlyIncome() != null ? profile.getMonthlyIncome() : 0)
                - (profile.getMonthlyExpense() != null ? profile.getMonthlyExpense() : 0);
        double yearlySaving = monthlySaving * 12 / 10000; // 元 → 万元

        // 三情景：悲观 = 期望 - 1倍波动，乐观 = 期望 + 1倍波动，下限保护
        List<Double> xs = new ArrayList<>();
        List<Double> pessimistic = new ArrayList<>();
        List<Double> expected = new ArrayList<>();
        List<Double> optimistic = new ArrayList<>();
        for (int y = 0; y <= years; y++) {
            xs.add((double) y);
            pessimistic.add(project(investable, yearlySaving, Math.max(expectedRet - vol, 0.0), y));
            expected.add(project(investable, yearlySaving, expectedRet, y));
            optimistic.add(project(investable, yearlySaving, expectedRet + vol, y));
        }

        // 蒙特卡洛：年化收益率 ~ N(期望, 波动率²)，每年抽一个随机收益率
        int runs = 1000;
        List<Double> finals = new ArrayList<>(runs);
        for (int i = 0; i < runs; i++) {
            double value = investable;
            for (int y = 0; y < years; y++) {
                double yearlyRet = expectedRet + vol * random.nextGaussian();
                value = value * (1 + yearlyRet) + yearlySaving;
            }
            finals.add(value);
        }
        Collections.sort(finals);

        return PlanResult.builder()
                .netWorth(investable)
                .monthlySaving(monthlySaving)
                .allocation(new LinkedHashMap<>(alloc))
                .riskLevel(profile.getRiskLevel())
                .years(xs)
                .pessimistic(round2(pessimistic))
                .expected(round2(expected))
                .optimistic(round2(optimistic))
                .mcMedian(round2(finals.get(runs / 2)))
                .mcP10(round2(finals.get((int) (runs * 0.1))))
                .mcP90(round2(finals.get((int) (runs * 0.9))))
                .mcRuns(runs)
                .build();
    }

    /** 复利推演：初始本金 + 每年末定投 */
    private double project(double principal, double yearlySaving, double annualRet, int years) {
        double value = principal;
        for (int i = 0; i < years; i++) {
            value = value * (1 + annualRet) + yearlySaving;
        }
        return value;
    }

    private List<Double> round2(List<Double> list) {
        List<Double> out = new ArrayList<>(list.size());
        for (double v : list) out.add(round2(v));
        return out;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
