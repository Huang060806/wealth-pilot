package com.wealthpilot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 单类资产的推演结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetLine implements Serializable {
    private String name;             // 现金/定存/股票/基金/房产/负债/每月结余再投资
    private double now;              // 当前规模（万元，负债为负）
    private double annualReturn;     // 采用的年化收益率
    private double terminal;         // N 年后中性预期规模
    private String note;             // 说明（如"按标普500近10年年化"）
}
