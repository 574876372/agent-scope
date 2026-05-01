package com.cl.agent.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.cl.agent.dto.AgentResponse;
import com.cl.agent.dto.ChatRequest;
import com.cl.agent.dto.ChatResponse;
import com.cl.agent.dto.CreateAgentRequest;
import com.cl.agent.enums.ModelProviderEnum;
import com.cl.agent.exception.BizException;
import com.cl.agent.model.AgentInfo;
import com.cl.agent.service.IAgentService;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.OpenAIChatModel;

/**
 * Agent 业务实现类
 * 负责 Agent 的创建、查询、删除及对话，使用内存 ConcurrentHashMap 缓存 Agent 实例
 */
@Service
public class AgentServiceImpl implements IAgentService {

    /** 内存缓存：Agent ID -> AgentInfo，支持并发访问 */
    private final ConcurrentHashMap<String, AgentInfo> agentCache = new ConcurrentHashMap<>();

    @Override
    public AgentResponse createAgent(CreateAgentRequest request) {
        OpenAIChatModel model = buildModel(request.getModelType(), request.getModelName());

        Agent agent = ReActAgent.builder()
                .name(request.getName())
                .model(model)
                .build();

        AgentInfo info = new AgentInfo();
        info.setId(UUID.randomUUID().toString());
        info.setName(request.getName());
        info.setModelType(request.getModelType());
        info.setModelName(model.getModelName());
        info.setStatus("active");
        info.setCreatedAt(LocalDateTime.now());
        info.setAgent(agent);

        agentCache.put(info.getId(), info);
        return toResponse(info);
    }

    @Override
    public List<AgentResponse> listAgents() {
        return agentCache.values().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public AgentResponse getAgent(String id) {
        AgentInfo info = agentCache.get(id);
        if (info == null) {
            throw new BizException(404, "Agent 不存在: " + id);
        }
        return toResponse(info);
    }

    @Override
    public void deleteAgent(String id) {
        if (agentCache.remove(id) == null) {
            throw new BizException(404, "Agent 不存在: " + id);
        }
    }

    @Override
    public ChatResponse chat(String id, ChatRequest request) {
        AgentInfo info = agentCache.get(id);
        if (info == null) {
            throw new BizException(404, "Agent 不存在: " + id);
        }

        Msg reply = info.getAgent()
                .call(Msg.builder()
                        .textContent(request.getContent())
                        .role(MsgRole.USER)
                        .build())
                .block();

        ChatResponse response = new ChatResponse();
        response.setAgentId(id);
        response.setAgentName(info.getName());
        response.setContent(reply != null ? reply.getTextContent() : "");
        return response;
    }

    // ---------- 私有方法 ----------

    /**
     * 根据厂商类型和模型名称动态构建 OpenAIChatModel
     *
     * @param modelType 模型厂商类型，如 qwen / deepseek
     * @param modelName 模型名称，如 Qwen3.5-27B / deepseek-chat
     * @return 配置好的 OpenAIChatModel 实例
     * @throws BizException 当厂商类型不支持时抛出
     */
    private OpenAIChatModel buildModel(String modelType, String modelName) {
        ModelProviderEnum provider = ModelProviderEnum.of(modelType);
        return OpenAIChatModel.builder()
                .baseUrl(provider.getBaseUrl())
                .apiKey(provider.getApiKey())
                .modelName(modelName)
                .build();
    }

    private AgentResponse toResponse(AgentInfo info) {
        AgentResponse resp = new AgentResponse();
        resp.setId(info.getId());
        resp.setName(info.getName());
        resp.setModelType(info.getModelType());
        resp.setModelName(info.getModelName());
        resp.setStatus(info.getStatus());
        resp.setCreatedAt(info.getCreatedAt());
        return resp;
    }
}
