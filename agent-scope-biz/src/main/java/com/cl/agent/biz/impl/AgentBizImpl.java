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
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.HttpVersion;
import io.agentscope.core.model.transport.OkHttpTransport;
import io.agentscope.core.studio.StudioManager;
import io.agentscope.core.studio.StudioMessageHook;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
        Agent agent = buildAgent(
                request.getName(),
                request.getModelType(),
                request.getModelName(),
                request.getSystemPrompt()
        );

        AgentInfo info = new AgentInfo();
        info.setId(UUID.randomUUID().toString());
        info.setName(request.getName());
        info.setModelType(request.getModelType());
        info.setModelName(request.getModelName());
        info.setSystemPrompt(request.getSystemPrompt());
        info.setStatus("active");
        info.setUserId(UserContext.getUserId());

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
            // 若缓存中没有，则根据持久化信息重新构建（含 Studio Hook）
            agent = buildAgent(
                    info.getName(),
                    info.getModelType(),
                    info.getModelName(),
                    info.getSystemPrompt()
            );
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

    /**
     * 统一构建 ReActAgent 实例。
     * <p>若 Studio 已启用（studioManager 不为 null），则自动注入 StudioMessageHook，
     * 使 Agent 的完整推理链路（Thought / Action / Observation）同步至可视化面板。</p>
     *
     * @param name       Agent 名称
     * @param modelType  模型厂商类型
     * @param modelName  具体模型名称
     * @param sysPrompt  系统提示词
     * @return 构建完成的 Agent 实例
     */
    private Agent buildAgent(String name, String modelType, String modelName, String sysPrompt) {
        OpenAIChatModel model = buildModel(modelType, modelName);
        ReActAgent.Builder builder = ReActAgent.builder()
                .name(name)
                .model(model)
                .sysPrompt(sysPrompt);

        // 若 Studio 集成已启用且连接成功，注入可视化 Hook
        if (StudioManager.isInitialized()) {
            builder.hook(new StudioMessageHook(StudioManager.getClient()));
            log.info("[Studio] Agent [{}] 已注册 StudioMessageHook，推理过程将同步至可视化面板", name);
        }

        return builder.build();
    }

    /**
     * 构建 OpenAI 兼容协议的聊天模型实例。
     *
     * @param modelType 模型厂商枚举 key
     * @param modelName 模型名称
     * @return 配置完毕的 {@link OpenAIChatModel}
     */
    private OpenAIChatModel buildModel(String modelType, String modelName) {
        ModelProviderEnum provider = ModelProviderEnum.of(modelType);
        
        // 强制在客户端使用 HTTP/1.1 协议
        // 目的：防止 JDK HttpClient 用默认 HTTP/2 协议请求 DeepSeek/通义等接口时，
        // 在 SSE（流式）结束或连接复用时由于代理或网关发送的 RST_STREAM / 提前断开，
        // 导致抛出 "java.io.IOException: closed" 或 "EOFReachedException" 异常。
        HttpTransportConfig config = HttpTransportConfig.builder()
                .httpVersion(HttpVersion.HTTP_1_1)
                .build();
                
        // 使用 OkHttp 传输层实例替代 JDK HttpClient，以提升复杂网络代理和高并发下 SSE/NDJSON 流式传输的健壮性
        HttpTransport transport = OkHttpTransport.builder()
                .config(config)
                .build();
                
        return OpenAIChatModel.builder()
                .baseUrl(provider.getBaseUrl())
                .apiKey(provider.getApiKey())
                .modelName(modelName)
                .httpTransport(transport)
                .build();
    }

    private AgentResponse toResponse(AgentInfo info) {
        AgentResponse resp = new AgentResponse();
        resp.setId(info.getId());
        resp.setName(info.getName());
        resp.setModelType(info.getModelType());
        resp.setModelName(info.getModelName());
        resp.setStatus(info.getStatus());
        resp.setCreateTime(info.getCreateTime());
        resp.setSystemPrompt(info.getSystemPrompt());
        return resp;
    }
}
