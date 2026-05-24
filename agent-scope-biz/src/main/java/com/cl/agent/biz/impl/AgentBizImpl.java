package com.cl.agent.biz.impl;

import com.cl.agent.tool.core.AgentToolkitFactory;
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
import com.cl.agent.service.IAgentToolRelService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.HttpVersion;
import io.agentscope.core.model.transport.OkHttpTransport;
import io.agentscope.core.studio.StudioManager;
import io.agentscope.core.studio.StudioMessageHook;
import io.agentscope.core.tool.Toolkit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AgentBizImpl implements IAgentBiz {

    @Autowired
    private IAgentService agentService;

    @Autowired
    private AgentToolkitFactory agentToolkitFactory;

    @Autowired
    private IAgentToolRelService agentToolRelService;


    /** 运行时 Agent 实例缓存 (不持久化，仅存放在内存中) */
    private final ConcurrentHashMap<String, Agent> agentInstanceCache = new ConcurrentHashMap<>();

    @Override
    public AgentResponse createAgent(CreateAgentRequest request) {
        // 生成 Agent ID
        String agentId = UUID.randomUUID().toString();

        // 构建 Agent 实例（使用请求中指定的工具列表）
        Agent agent = buildAgent(
                request.getName(),
                request.getModelType(),
                request.getModelName(),
                request.getSystemPrompt(),
                request.getToolNames()
        );

        // 持久化 Agent 基本信息
        AgentInfo info = new AgentInfo();
        info.setId(agentId);
        info.setName(request.getName());
        info.setModelType(request.getModelType());
        info.setModelName(request.getModelName());
        info.setSystemPrompt(request.getSystemPrompt());
        info.setStatus("active");
        info.setUserId(UserContext.getUserId());
        agentService.save(info);

        // 保存 Agent-工具关联关系
        if (request.getToolNames() != null && !request.getToolNames().isEmpty()) {
            agentToolRelService.replaceToolsForAgent(agentId, request.getToolNames());
        }

        // 缓存运行时实例
        agentInstanceCache.put(agentId, agent);

        log.info("成功创建 Agent: ID={}, 名称={}, 工具={}", agentId, info.getName(), request.getToolNames());
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
        agentToolRelService.deleteByAgentId(id);
        agentInstanceCache.remove(id);
        log.info("删除 Agent: ID={}，已级联清理工具关联", id);
    }

    @Override
    public AgentResponse updateAgent(String id, CreateAgentRequest request) {
        AgentInfo info = agentService.getById(id);
        if (info == null) {
            throw new BizException(404, "Agent 不存在: " + id);
        }

        // 更新基本信息
        if (request.getName() != null) {
            info.setName(request.getName());
        }
        if (request.getModelType() != null) {
            info.setModelType(request.getModelType());
        }
        if (request.getModelName() != null) {
            info.setModelName(request.getModelName());
        }
        if (request.getSystemPrompt() != null) {
            info.setSystemPrompt(request.getSystemPrompt());
        }
        agentService.save(info);

        // 更新工具关联
        if (request.getToolNames() != null) {
            agentToolRelService.replaceToolsForAgent(id, request.getToolNames());
        }

        // 清除缓存，下次对话时会重建 Agent 实例（使用新的工具集）
        agentInstanceCache.remove(id);

        log.info("更新 Agent: ID={}, 名称={}, 工具={}", id, info.getName(), request.getToolNames());
        return toResponse(info);
    }

    @Override
    public ChatResponse chat(String id, ChatRequest request) {
        AgentInfo info = agentService.getById(id);
        if (info == null) {
            throw new BizException(404, "Agent 不存在: " + id);
        }

        Agent agent = resolveAgent(id, info);

        long startMs = System.currentTimeMillis();
        log.info("[Model] 开始调用模型(同步), agentId={}, agentName={}, model={}",
                id, info.getName(), info.getModelName());
        Msg reply;
        try {
            reply = agent.call(Msg.builder()
                            .textContent(request.getContent())
                            .role(MsgRole.USER)
                            .build())
                    .block();
            log.info("[Model] 模型同步调用完成, agentId={}, agentName={}, model={}, costMs={}",
                    id, info.getName(), info.getModelName(), System.currentTimeMillis() - startMs);
        } catch (Exception e) {
            log.error("[Model] 模型同步调用异常, agentId={}, agentName={}, model={}, costMs={}",
                    id, info.getName(), info.getModelName(), System.currentTimeMillis() - startMs, e);
            throw e;
        }

        ChatResponse response = new ChatResponse();
        response.setAgentId(id);
        response.setAgentName(info.getName());
        response.setContent(reply != null ? reply.getTextContent() : "");
        return response;
    }

    @Override
    public Flux<Event> chatStream(String id, ChatRequest request) {
        AgentInfo info = agentService.getById(id);
        if (info == null) {
            return Flux.error(new BizException(404, "Agent 不存在: " + id));
        }

        Agent agent = resolveAgent(id, info);
        Msg userMsg = Msg.builder()
                .textContent(request.getContent())
                .role(MsgRole.USER)
                .build();

        StreamOptions options = StreamOptions.builder()
                .eventTypes(EventType.REASONING, EventType.TOOL_RESULT, EventType.AGENT_RESULT)
                .incremental(true)
                .includeReasoningChunk(true)
                .includeReasoningResult(false)
                .build();

        AtomicLong startMs = new AtomicLong();
        AtomicBoolean firstEvent = new AtomicBoolean(true);

        return agent.stream(List.of(userMsg), options)
                .doOnSubscribe(sub -> {
                    startMs.set(System.currentTimeMillis());
                    log.info("[Model] 开始调用模型(流式), agentId={}, agentName={}, model={}",
                            id, info.getName(), info.getModelName());
                })
                .doOnNext(event -> {
                    if (firstEvent.compareAndSet(true, false)) {
                        log.info("[Model] 模型流式首包返回, agentId={}, model={}, ttftMs={}",
                                id, info.getModelName(), System.currentTimeMillis() - startMs.get());
                    }
                })
                .doOnComplete(() -> log.info("[Model] 模型流式调用完成, agentId={}, agentName={}, model={}, costMs={}",
                        id, info.getName(), info.getModelName(), System.currentTimeMillis() - startMs.get()))
                .doOnError(e -> log.error("[Model] 模型流式调用异常, agentId={}, agentName={}, model={}, costMs={}",
                        id, info.getName(), info.getModelName(), System.currentTimeMillis() - startMs.get(), e));
    }

    /**
     * 从缓存获取或重建 Agent 实例。
     * <p>重建时会查询数据库获取工具关联列表，确保 Agent 使用正确的工具集。</p>
     */
    private Agent resolveAgent(String id, AgentInfo info) {
        Agent agent = agentInstanceCache.get(id);
        if (agent == null) {
            // 从数据库查询该 Agent 关联的工具列表
            List<String> toolNames = agentToolRelService.getToolNamesByAgentId(id);
            agent = buildAgent(
                    info.getName(),
                    info.getModelType(),
                    info.getModelName(),
                    info.getSystemPrompt(),
                    toolNames
            );
            agentInstanceCache.put(id, agent);
        }
        return agent;
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
     * @param toolNames  Agent 授权使用的工具名称列表，null 时使用默认内置工具
     * @return 构建完成的 Agent 实例
     */
    private Agent buildAgent(String name, String modelType, String modelName, String sysPrompt,
                             List<String> toolNames) {
        OpenAIChatModel model = buildModel(modelType, modelName);
        ReActAgent.Builder builder = ReActAgent.builder()
                .name(name)
                .model(model)
                .sysPrompt(sysPrompt);

        Toolkit toolkit = agentToolkitFactory.createToolkit(toolNames);
        if (toolkit != null) {
            builder.toolkit(toolkit);
            log.info("[Tool] Agent [{}] 已注入 {} 个工具", name, toolkit.getToolNames().size());
        }

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
                .stream(true)
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
        resp.setToolNames(agentToolRelService.getToolNamesByAgentId(info.getId()));
        return resp;
    }
}
