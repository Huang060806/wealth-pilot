package com.wealthpilot.service;

import com.wealthpilot.model.AssetLine;
import com.wealthpilot.model.AssetProfile;
import com.wealthpilot.model.PlanResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 确定性金融计算引擎：所有数字都在这里算，LLM 只负责理解和表达。
 * <p>
 * 核心逻辑：每一类资产按自己的真实收益率独立滚动（现金≈货基、定存≈债券、
 * 股票/基金≈权益基准、房产按房产增值假设、负债按贷款利率增长），
 * 每月结余按建议配置再投资。总量 = 各部分之和。
 */
@Service
@RequiredArgsConstructor
public class FinanceService {

    private final Random random = new Random();
    private final MarketDataService marketDataService;

    /** 风险偏好 → 新增资金的配置比例 */
    private static final Map<String, Map<String, Double>> ALLOCATIONS = Map.of(
            "conservative", Map.of("股票", 0.2, "债券", 0.5, "现金", 0.3),
            "balanced",     Map.of("股票", 0.5, "债券", 0.3, "现金", 0.2),
            "aggressive",   Map.of("股票", 0.75, "债券", 0.15, "现金", 0.10)
    );

    private static final double REAL_ESTATE_RETURN = 0.025;  // 房产长期增值假设
    private static final double DEBT_INTEREST = 0.045;       // 负债利率假设
    private static final double DEPOSIT_RETURN = 0.022;      // 定期存款利率假设

    public Map<String, Double> allocationFor(String riskLevel) {
        return ALLOCATIONS.getOrDefault(riskLevel, ALLOCATIONS.get("balanced"));
    }

    public PlanResult generatePlan(AssetProfile profile) {
        int years = profile.getYears() != null && profile.getYears() > 0 ? profile.getYears() : 10;
        Map<String, Double> alloc = allocationFor(profile.getRiskLevel());

        double[] stockP = marketDataService.paramsOf("股票");
        double[] bondP = marketDataService.paramsOf("债券");
        double[] cashP = marketDataService.paramsOf("现金");

        // ============ ① 逐资产分析：每类资产按自身收益率滚动 ============
        List<AssetLine> lines = new ArrayList<>();
        addLine(lines, "现金/活期", profile.getCash(), cashP[0], years, "按货币基金假设");
        addLine(lines, "定期存款", profile.getDeposit(), DEPOSIT_RETURN, years, "按定存利率假设");
        addLine(lines, "股票", profile.getStocks(), stockP[0], years, "按标普500近10年实际年化");
        addLine(lines, "基金", profile.getFunds(), blend(stockP[0], bondP[0], 0.7), years, "按 70%股+30%债 混合假设");
        addLine(lines, "房产", profile.getRealEstate(), REAL_ESTATE_RETURN, years, "按长期房产增值假设");
        if (nz(profile.getDebts()) > 0) {
            addLine(lines, "负债(利息增长)", -nz(profile.getDebts()), DEBT_INTEREST, years, "按贷款利率假设，为负值");
        }

        // 月结余按建议配置再投资（每年末投入，拿组合收益）
        double monthlySaving = nz(profile.getMonthlyIncome()) - nz(profile.getMonthlyExpense());
        double yearlySaving = monthlySaving * 12 / 10000;
        double blendedRet = blend3(stockP[0], bondP[0], cashP[0], alloc);
        double savingTerminal = yearlySaving > 0 ? fvOfAnnuity(yearlySaving, blendedRet, years) : 0;
        if (yearlySaving > 0) {
            lines.add(AssetLine.builder()
                    .name("每月结余再投资").now(0).annualReturn(blendedRet)
                    .terminal(round2(savingTerminal))
                    .note("月结余按建议配置 " + allocText(alloc) + " 投资").build());
        }

        // ============ ② 总量三情景曲线 ============
        // 组合波动率 = 按各资产权重加权（含房产低波动、负债利率固定）
        double investableNow = profile.netWorth();
        double blendedVol = blendedVolatility(stockP[1], bondP[1], cashP[1], alloc);

        List<Double> xs = new ArrayList<>();
        List<Double> pessimistic = new ArrayList<>();
        List<Double> expected = new ArrayList<>();
        List<Double> optimistic = new ArrayList<>();
        for (int y = 0; y <= years; y++) {
            xs.add((double) y);
            expected.add(round2(totalAt(profile, alloc, stockP[0], bondP[0], cashP[0], blendedRet, y)));
            // 悲观/乐观：权益类收益 ±1倍波动，其他资产不动（确定性部分保持）
            pessimistic.add(round2(totalAt(profile, alloc,
                    Math.max(stockP[0] - stockP[1], -0.2), bondP[0], cashP[0],
                    Math.max(blendedRet - blendedVol, 0.0), y)));
            optimistic.add(round2(totalAt(profile, alloc,
                    stockP[0] + stockP[1], bondP[0], cashP[0], blendedRet + blendedVol, y)));
        }

        // ============ ③ 蒙特卡洛：权益部分按正态波动，确定部分不动 ============
        int runs = 1000;
        List<Double> finals = new ArrayList<>(runs);
        for (int i = 0; i < runs; i++) {
            finals.add(round2(simulateOnce(profile, alloc, stockP, bondP, cashP, years)));
        }
        Collections.sort(finals);

        // 当前配置比例（可投资资产中 股/债/现金 的占比）
        Map<String, Double> currentAlloc = currentAllocation(profile);

        return PlanResult.builder()
                .netWorth(round2(investableNow))
                .monthlySaving(round2(monthlySaving))
                .allocation(new LinkedHashMap<>(alloc))
                .riskLevel(profile.getRiskLevel())
                .years(xs)
                .pessimistic(pessimistic)
                .expected(expected)
                .optimistic(optimistic)
                .assetLines(lines)
                .currentAllocation(currentAlloc)
                .mcMedian(finals.get(runs / 2))
                .mcP10(finals.get((int) (runs * 0.1)))
                .mcP90(finals.get((int) (runs * 0.9)))
                .mcRuns(runs)
                .build();
    }

    /** 第 y 年的总净资产：存量逐资产滚动 + 结余再投资 */
    private double totalAt(AssetProfile p, Map<String, Double> alloc,
                           double stockRet, double bondRet, double cashRet, double savingRet, int y) {
        double total = 0;
        total += compound(nz(p.getCash()), cashRet, y);
        total += compound(nz(p.getDeposit()), DEPOSIT_RETURN, y);
        total += compound(nz(p.getStocks()), stockRet, y);
        total += compound(nz(p.getFunds()), blend(stockRet, bondRet, 0.7), y);
        total += compound(nz(p.getRealEstate()), REAL_ESTATE_RETURN, y);
        total -= compound(nz(p.getDebts()), DEBT_INTEREST, y);

        double yearlySaving = (nz(p.getMonthlyIncome()) - nz(p.getMonthlyExpense())) * 12 / 10000;
        if (yearlySaving > 0) {
            total += fvOfAnnuity(yearlySaving, savingRet, y);
        }
        return total;
    }

    /** 蒙特卡洛单次路径：权益类（股票+基金+结余的股票部分）每年抽随机收益率，其他确定 */
    private double simulateOnce(AssetProfile p, Map<String, Double> alloc,
                                double[] stockP, double[] bondP, double[] cashP, int years) {
        double stocks = nz(p.getStocks());
        double funds = nz(p.getFunds());
        double savingStock = 0, savingBond = 0, savingCash = 0;
        double yearlySaving = (nz(p.getMonthlyIncome()) - nz(p.getMonthlyExpense())) * 12 / 10000;

        for (int y = 0; y < years; y++) {
            double stockRet = stockP[0] + stockP[1] * random.nextGaussian();
            double bondRet = bondP[0] + bondP[1] * random.nextGaussian();
            stocks = stocks * (1 + stockRet);
            funds = funds * (1 + blend(stockRet, bondRet, 0.7));
            if (yearlySaving > 0) {
                savingStock = savingStock * (1 + stockRet) + yearlySaving * alloc.get("股票");
                savingBond = savingBond * (1 + bondRet) + yearlySaving * alloc.get("债券");
                savingCash = savingCash * (1 + cashP[0]) + yearlySaving * alloc.get("现金");
            }
        }
        double total = stocks + funds + savingStock + savingBond + savingCash
                + compound(nz(p.getCash()), cashP[0], years)
                + compound(nz(p.getDeposit()), DEPOSIT_RETURN, years)
                + compound(nz(p.getRealEstate()), REAL_ESTATE_RETURN, years)
                - compound(nz(p.getDebts()), DEBT_INTEREST, years);
        return total;
    }

    /** 当前资产中 股/债/现金 的占比（基金拆 70%股 30%债，房产不计入可投资产） */
    private Map<String, Double> currentAllocation(AssetProfile p) {
        double stock = nz(p.getStocks()) + nz(p.getFunds()) * 0.7;
        double bond = nz(p.getDeposit()) + nz(p.getFunds()) * 0.3;
        double cash = nz(p.getCash());
        double total = stock + bond + cash;
        Map<String, Double> result = new LinkedHashMap<>();
        if (total <= 0) {
            result.put("股票", 0.0); result.put("债券", 0.0); result.put("现金", 1.0);
            return result;
        }
        result.put("股票", round2(stock / total));
        result.put("债券", round2(bond / total));
        result.put("现金", round2(cash / total));
        return result;
    }

    private void addLine(List<AssetLine> lines, String name, Double amount,
                         double annualRet, int years, String note) {
        if (amount == null || amount == 0) return;
        lines.add(AssetLine.builder()
                .name(name).now(round2(Math.abs(amount)))
                .annualReturn(round4(annualRet))
                .terminal(round2(compound(amount, annualRet, years)))
                .note(note).build());
    }

    private double compound(double principal, double annualRet, int years) {
        return principal * Math.pow(1 + annualRet, years);
    }

    /** 年金终值：每年末定投 */
    private double fvOfAnnuity(double yearlySaving, double annualRet, int years) {
        if (annualRet == 0) return yearlySaving * years;
        return yearlySaving * (Math.pow(1 + annualRet, years) - 1) / annualRet;
    }

    private double blend(double a, double b, double weightA) {
        return a * weightA + b * (1 - weightA);
    }

    private double blend3(double stock, double bond, double cash, Map<String, Double> alloc) {
        return stock * alloc.get("股票") + bond * alloc.get("债券") + cash * alloc.get("现金");
    }

    private double blendedVolatility(double stockVol, double bondVol, double cashVol, Map<String, Double> alloc) {
        return stockVol * alloc.get("股票") + bondVol * alloc.get("债券") + cashVol * alloc.get("现金");
    }

    private String allocText(Map<String, Double> alloc) {
        StringBuilder sb = new StringBuilder();
        alloc.forEach((k, v) -> sb.append(k).append((int) (v * 100)).append("% "));
        return sb.toString().trim();
    }

    private double nz(Double v) { return v == null ? 0 : v; }
    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }
}
