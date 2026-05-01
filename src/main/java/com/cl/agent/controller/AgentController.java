package com.cl.agent.controller;

import com.cl.agent.dto.AgentResponse;
import com.cl.agent.dto.ChatRequest;
import com.cl.agent.dto.ChatResponse;
import com.cl.agent.dto.CreateAgentRequest;
import com.cl.agent.service.IAgentService;
import com.cl.agent.enums.ModelProviderEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/agents")
@CrossOrigin(origins = "*")
public class AgentController {

    @Autowired
    private IAgentService agentService;

    /** 获取支持的模型厂商及其模型列表 */
    @GetMapping("/models")
    public ResponseEntity<List<Map<String, Object>>> getModelProviders() {
        List<Map<String, Object>> providers = Arrays.stream(ModelProviderEnum.values())
                .map(p -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("type", p.getType());
                    map.put("models", p.getModels());
                    return map;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(providers);
    }


    /** 创建 Agent */
    @PostMapping
    public ResponseEntity<AgentResponse> createAgent(@RequestBody CreateAgentRequest request) {
        return ResponseEntity.ok(agentService.createAgent(request));
    }

    /** 获取所有 Agent */
    @GetMapping
    public ResponseEntity<List<AgentResponse>> listAgents() {
        return ResponseEntity.ok(agentService.listAgents());
    }

    /** 获取单个 Agent */
    @GetMapping("/{id}")
    public ResponseEntity<AgentResponse> getAgent(@PathVariable String id) {
        return ResponseEntity.ok(agentService.getAgent(id));
    }

    /** 删除 Agent */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAgent(@PathVariable String id) {
        agentService.deleteAgent(id);
        return ResponseEntity.noContent().build();
    }

    /** 向 Agent 发送消息 */
    @PostMapping("/{id}/chat")
    public ResponseEntity<ChatResponse> chat(@PathVariable String id, @RequestBody ChatRequest request) {
        return ResponseEntity.ok(agentService.chat(id, request));
    }
}
