package com.wealthpilot.service;

import com.wealthpilot.model.AssetProfile;
import com.wealthpilot.model.PlanResult;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级存储（MVP 用内存，重启即清；二期可换 Redis）
 */
@Service
public class ProfileStore {

    private final Map<String, AssetProfile> profiles = new ConcurrentHashMap<>();
    private final Map<String, PlanResult> plans = new ConcurrentHashMap<>();

    public AssetProfile profileOf(String sessionId) {
        return profiles.computeIfAbsent(sessionId, k -> new AssetProfile());
    }

    public void savePlan(String sessionId, PlanResult plan) {
        plans.put(sessionId, plan);
    }

    public PlanResult planOf(String sessionId) {
        return plans.get(sessionId);
    }
}
