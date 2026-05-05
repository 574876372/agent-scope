package com.cl.agent.biz.impl;

import com.cl.agent.biz.IAgentBiz;
import com.cl.agent.commons.UserContext;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AgentBizImpl implements IAgentBiz {

    @Autowired
    private IAgentService agentService;

    /** 运行时 Agent 实例缓存 (不持久化，仅存放在内存中) */
    private final ConcurrentHashMap<String, Agent> agentInstanceCache = new ConcurrentHashMap<>();

    @Override
    public AgentResponse createAgent(CreateAgentRequest request) {
        OpenAIChatModel model = buildModel(request.getModelType(), request.getModelName());

        Agent agent = ReActAgent.builder()
                .name(request.getName())
                .model(model)
                .sysPrompt(request.getSystemPrompt())
                .build();

        AgentInfo info = new AgentInfo();
        info.setId(UUID.randomUUID().toString());
        info.setName(request.getName());
        info.setModelType(request.getModelType());
        info.setModelName(model.getModelName());
        info.setSystemPrompt(request.getSystemPrompt());
        info.setStatus("active");
        info.setUserId(UserContext.getUserId());
        info.setCreatedAt(LocalDateTime.now());

        // 保存到 Service 层
        agentService.save(info);
        // 保存运行时实例
        agentInstanceCache.put(info.getId(), agent);

        log.info("成功创建 Agent: ID={}, 名称={}", info.getId(), info.getName());
        return toResponse(info);
    }

    @Override
    public List<AgentResponse> listAgents() {
        String userId = UserContext.getUserId();
        return agentService.listAll().stream()
                .filter(info -> userId == null || userId.equals(info.getUserId()))
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public AgentResponse getAgent(String id) {
        AgentInfo info = agentService.getById(id);
        if (info == null) {
            throw new BizException(404, "Agent 不存在: " + id);
        }
        return toResponse(info);
    }

    @Override
    public void deleteAgent(String id) {
        agentService.deleteById(id);
        agentInstanceCache.remove(id);
    }

    @Override
    public ChatResponse chat(String id, ChatRequest request) {
        AgentInfo info = agentService.getById(id);
        if (info == null) {
            throw new BizException(404, "Agent 不存在: " + id);
        }

        // 获取或重新构建 Agent 实例
        Agent agent = agentInstanceCache.get(id);
        if (agent == null) {
            // 如果缓存中没有，则根据 info 重新构建 (这里简化处理)
            agent = ReActAgent.builder()
                    .name(info.getName())
                    .model(buildModel(info.getModelType(), info.getModelName()))
                    .sysPrompt(info.getSystemPrompt())
                    .build();
            agentInstanceCache.put(id, agent);
        }

        Msg reply = agent.call(Msg.builder()
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
        resp.setSystemPrompt(info.getSystemPrompt());
        return resp;
    }
}
