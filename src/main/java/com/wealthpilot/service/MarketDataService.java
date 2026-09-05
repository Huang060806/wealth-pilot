package com.wealthpilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 真实行情数据源（新浪财经美股接口，公开免鉴权）：
 * SPY（标普500ETF，股票基准）+ AGG（美国综合债券ETF，债券基准），近 10 年日K，
 * 计算年化收益率（CAGR）和年化波动率（日对数收益率标准差 × √252）。
 * 内存缓存 24 小时；接口不可用时回退到默认假设。
 */
@Slf4j
@Service
public class MarketDataService {

    /** 基准：{资产类别 → {新浪代码, 名称}} */
    private static final Map<String, String[]> BENCHMARKS = Map.of(
            "股票", new String[]{"spy", "标普500ETF(SPY)"},
            "债券", new String[]{"agg", "美国综合债券ETF(AGG)"}
    );

    /** 默认假设（接口失败时回退）：{年化收益率, 年化波动率} */
    private static final Map<String, double[]> FALLBACK = Map.of(
            "股票", new double[]{0.07, 0.20},
            "债券", new double[]{0.03, 0.05},
            "现金", new double[]{0.015, 0.005}
    );

    private static final String KLINE_URL =
            "https://stock.finance.sina.com.cn/usstock/api/jsonp_v2.php/x/US_MinKService.getDailyK?symbol=%s&___qn=3&dpc=1";

    private static final long CACHE_TTL_MS = 24 * 3600 * 1000;
    private static final int LOOKBACK_YEARS = 10;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, double[]> cache = new ConcurrentHashMap<>();
    private volatile long cacheTime = 0;

    /** 获取资产类别的 {年化收益率, 年化波动率} */
    public double[] paramsOf(String assetClass) {
        if ("现金".equals(assetClass)) {
            return FALLBACK.get("现金"); // 货币基金类无公开指数，用合理假设
        }
        refreshIfNeeded();
        return cache.getOrDefault(assetClass, FALLBACK.get(assetClass));
    }

    /** 数据状态（前端展示数据来源用） */
    public Map<String, Object> status() {
        refreshIfNeeded();
        Map<String, Object> result = new LinkedHashMap<>();
        for (String cls : List.of("股票", "债券", "现金")) {
            double[] p = paramsOf(cls);
            String source;
            if ("现金".equals(cls)) {
                source = "假设（货币基金约1.5%）";
            } else {
                source = cache.containsKey(cls)
                        ? BENCHMARKS.get(cls)[1] + " 近10年实际行情"
                        : "默认假设（行情接口暂不可用）";
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("annualReturn", p[0]);
            item.put("volatility", p[1]);
            item.put("source", source);
            result.put(cls, item);
        }
        return result;
    }

    private synchronized void refreshIfNeeded() {
        if (System.currentTimeMillis() - cacheTime < CACHE_TTL_MS) {
            return;
        }
        for (Map.Entry<String, String[]> e : BENCHMARKS.entrySet()) {
            try {
                List<double[]> klines = fetchKlines(e.getValue()[0]);
                double[] params = computeParams(klines);
                cache.put(e.getKey(), params);
                log.info("行情参数已更新: {}({}) 近{}年 年化收益率={}%, 波动率={}%",
                        e.getKey(), e.getValue()[1], LOOKBACK_YEARS, fmt(params[0]), fmt(params[1]));
            } catch (Exception ex) {
                log.warn("拉取{}行情失败，沿用缓存/默认值: {}", e.getKey(), ex.getMessage());
            }
        }
        cacheTime = System.currentTimeMillis();
    }

    /** 拉取日K并过滤到近10年，返回 {epochDay, close} 列表 */
    private List<double[]> fetchKlines(String symbol) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format(KLINE_URL, symbol)))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Mozilla/5.0")
                .GET().build();
        String body = http.send(request, HttpResponse.BodyHandlers.ofString()).body();

        // 去掉 JSONP 包装: /* ... */x([...])
        int start = body.indexOf("x(");
        if (start < 0) throw new IllegalStateException("响应格式异常");
        JsonNode rows = mapper.readTree(body.substring(start + 2, body.lastIndexOf(')')));

        long cutoff = LocalDate.now().minusYears(LOOKBACK_YEARS).toEpochDay();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        List<double[]> out = new ArrayList<>();
        for (JsonNode row : rows) {
            long day = LocalDate.parse(row.path("d").asText(), fmt).toEpochDay();
            if (day < cutoff) continue;
            double close = row.path("c").asDouble();
            if (close > 0) out.add(new double[]{day, close});
        }
        if (out.size() < 250) {
            throw new IllegalStateException("数据量不足: " + out.size());
        }
        return out;
    }

    /** 年化收益率 = 区间CAGR；年化波动率 = 日对数收益率标准差 × √252 */
    private double[] computeParams(List<double[]> klines) {
        int n = klines.size();
        double first = klines.get(0)[1];
        double last = klines.get(n - 1)[1];
        double years = (klines.get(n - 1)[0] - klines.get(0)[0]) / 365.25;
        double cagr = Math.pow(last / first, 1.0 / years) - 1;

        double[] logReturns = new double[n - 1];
        for (int i = 1; i < n; i++) {
            logReturns[i - 1] = Math.log(klines.get(i)[1] / klines.get(i - 1)[1]);
        }
        double mean = 0;
        for (double r : logReturns) mean += r;
        mean /= logReturns.length;
        double var = 0;
        for (double r : logReturns) var += (r - mean) * (r - mean);
        var /= (logReturns.length - 1);
        double annualVol = Math.sqrt(var) * Math.sqrt(252);

        return new double[]{cagr, annualVol};
    }

    private String fmt(double v) {
        return String.format("%.2f", v * 100);
    }
}
