package com.wealthpilot.controller;

import com.wealthpilot.model.AssetProfile;
import com.wealthpilot.model.PlanResult;
import com.wealthpilot.service.AgentService;
import com.wealthpilot.service.MarketDataService;
import com.wealthpilot.service.ProfileStore;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin
public class ChatController {

    private final AgentService agentService;
    private final ProfileStore store;
    private final MarketDataService marketDataService;

    /** 对话接口 */
    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody Map<String, String> body) {
        String sessionId = body.getOrDefault("sessionId", "default");
        String reply = agentService.chat(sessionId, body.get("message"));
        return Map.of("reply", reply);
    }

    /** 当前资产档案（前端调试面板用） */
    @GetMapping("/profile/{sessionId}")
    public AssetProfile profile(@PathVariable String sessionId) {
        return store.profileOf(sessionId);
    }

    /** 行情参数状态（各资产类别年化收益率/波动率及数据来源） */
    @GetMapping("/market/params")
    public Map<String, Object> marketParams() {
        return marketDataService.status();
    }

    /** 规划结果（前端图表用；未生成返回 204） */
    @GetMapping("/plan/{sessionId}")
    public PlanResult plan(@PathVariable String sessionId) {
        return store.planOf(sessionId);
    }
}
