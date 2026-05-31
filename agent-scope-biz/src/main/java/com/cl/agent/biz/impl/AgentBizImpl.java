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
import com.cl.agent.model.ChatMessage;
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

    /**
     * 创建并持久化一个新的 Agent 实例。
     * <p>根据配置动态构建对应的 `ReActAgent`，并将其缓存至内存，同时把基本配置和关联工具记录到数据库中。</p>
     *
     * @param request 创建 Agent 的请求参数体，包含名称、模型厂商、具体模型、人设 Prompt、授权工具以及记忆模式和限制轮数等信息
     * @return AgentResponse 创建成功的 Agent 详细配置信息响应对象
     */
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
        // 记忆管理配置：memoryMode / maxTurns（null 表示使用全局默认）
        info.setMemoryMode(request.getMemoryMode());
        info.setMaxTurns(request.getMaxTurns());
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

    /**
     * 列出当前登录用户名下的所有活跃 Agent。
     * <p>根据用户上下文中的当前用户 ID 进行数据隔离筛选。</p>
     *
     * @return List&lt;AgentResponse&gt; 属于当前用户的 Agent 详细配置信息列表
     */
    @Override
    public List<AgentResponse> listAgents() {
        String userId = UserContext.getUserId();
        return agentService.listAll().stream()
                .filter(info -> userId == null || userId.equals(info.getUserId()))
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * 获取指定 ID 的 Agent 详细配置信息。
     *
     * @param id Agent 唯一标识符 ID
     * @return AgentResponse 获取到的 Agent 详细信息对象
     * @throws BizException 当对应的 Agent 不存在时抛出 404 错误
     */
    @Override
    public AgentResponse getAgent(String id) {
        AgentInfo info = agentService.getById(id);
        if (info == null) {
            throw new BizException(404, "Agent 不存在: " + id);
        }
        return toResponse(info);
    }

    /**
     * 删除指定 ID 的 Agent，并自动级联清理该 Agent 的工具关联和内存缓存。
     *
     * @param id 待删除 of Agent 唯一标识符 ID
     */
    @Override
    public void deleteAgent(String id) {
        agentService.deleteById(id);
        agentToolRelService.deleteByAgentId(id);
        agentInstanceCache.remove(id);
        log.info("删除 Agent: ID={}，已级联清理工具关联", id);
    }

    /**
     * 更新指定 ID 的 Agent 配置。
     * <p>对基本字段（名称、模型、Prompt等）进行增量更新并写回数据库，同时清理该 Agent 的运行时实例缓存以使配置在下次对话时生效。</p>
     *
     * @param id      待更新的 Agent 唯一标识符 ID
     * @param request 包含新配置项的请求体
     * @return AgentResponse 更新后的 Agent 详细配置响应对象
     * @throws BizException 当对应的 Agent 不存在时抛出 404 错误
     */
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
        // 允许更新记忆模式和窗口配置
        if (request.getMemoryMode() != null) {
            info.setMemoryMode(request.getMemoryMode());
        }
        if (request.getMaxTurns() != null) {
            info.setMaxTurns(request.getMaxTurns());
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

    /**
     * 向指定的 Agent 发送同步对话请求。
     * <p>在执行对话前会首先刷新并重新注入通过 MemoryManager 处理好的历史记忆上下文。</p>
     *
     * @param id      Agent 唯一标识符 ID
     * @param request 包含用户当前提问及历史上下文的请求体
     * @return ChatResponse Agent 同步响应的结果对象
     * @throws BizException 当对应的 Agent 不存在时抛出 404 错误
     */
    @Override
    public ChatResponse chat(String id, ChatRequest request) {
        AgentInfo info = agentService.getById(id);
        if (info == null) {
            throw new BizException(404, "Agent 不存在: " + id);
        }

        Agent agent = resolveAgent(id, info);
        prepareAgentMemory(agent, request.getHistory());

        long startMs = System.currentTimeMillis();
        log.info("[Model] 开始调用模型(同步), agentId={}, agentName={}, model={}",
                id, info.getName(), info.getModelName());
        Msg reply;
        try {
            String userId = UserContext.getUserId();
            reply = agent.call(Msg.builder()
                            .textContent(request.getContent())
                            .role(MsgRole.USER)
                            .build())
                    .contextWrite(context -> context.put("userId", userId))
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

    /**
     * 向指定的 Agent 发送流式对话请求。
     * <p>在发起流式请求前先刷新注入历史上下文记忆，最后返回包含推理过程、工具结果和正式消息的响应式事件流。</p>
     *
     * @param id      Agent 唯一标识符 ID
     * @param request 包含用户当前提问及历史上下文的请求体
     * @return Flux&lt;Event&gt; 响应式事件流，包含智能体在交互过程中吐出的各种事件片段
     */
    @Override
    public Flux<Event> chatStream(String id, ChatRequest request) {
        AgentInfo info = agentService.getById(id);
        if (info == null) {
            return Flux.error(new BizException(404, "Agent 不存在: " + id));
        }

        Agent agent = resolveAgent(id, info);
        prepareAgentMemory(agent, request.getHistory());

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

        String userId = UserContext.getUserId();
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
                        id, info.getName(), info.getModelName(), System.currentTimeMillis() - startMs.get(), e))
                .contextWrite(context -> context.put("userId", userId));
    }

    /**
     * 获取指定 Agent 的缓存实例，如果缓存中不存在，则查询数据库的元配置重建 Agent 并将其加入缓存中。
     *
     * @param id   Agent 唯一标识符 ID
     * @param info 数据库中缓存的 Agent 基础配置实体
     * @return Agent 缓存或重建后的运行时智能体实例对象
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
     * 统一构建 ReActAgent 运行时对象。
     * <p>根据模型和 Prompt 信息创建模型实例，并绑定授权的工具包。若 Studio 可视化已开启，还会自动挂载 Studio 推理过程追踪 Hook。</p>
     *
     * @param name       Agent 的友好显示名称
     * @param modelType  模型提供商标识
     * @param modelName  目标调用的模型名称
     * @param sysPrompt  系统提示词（人设设定）
     * @param toolNames  授权关联的工具名称列表
     * @return Agent 构建好的运行时 ReActAgent 实例
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
     * 根据模型厂商和名称构建 OpenAI 兼容协议大模型客户端。
     * <p>此处强制底层使用 HTTP/1.1 以规避 HTTP/2 在 SSE 长连接断开或复用时的部分不稳定问题，同时使用更具弹性的 OkHttp 传输层。</p>
     *
     * @param modelType 模型厂商名称
     * @param modelName 具体的模型名称
     * @return OpenAIChatModel 实例化完成的 LLM 客户端
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
                
        // 使用 OkHttp 传输层实例替代 JDK HttpClient，以提升复杂网络代理 and 高并发下 SSE/NDJSON 流式传输的健壮性
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

    /**
     * 将数据库持久化实体 AgentInfo 转换为对外数据传输 DTO 响应对象，并补全关联工具信息。
     *
     * @param info 数据库存储的 Agent 实体对象
     * @return AgentResponse 包含完整信息的 DTO 对象
     */
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
        // 回填记忆配置供前端展示和编辑时回显
        resp.setMemoryMode(info.getMemoryMode());
        resp.setMaxTurns(info.getMaxTurns());
        return resp;
    }

    /**
     * 将字符串类型的 Role 安全转换为 AgentScope 原生的角色 MsgRole 枚举。
     *
     * @param role 角色名称（如 "user"、"assistant"、"system"）
     * @return MsgRole 转换后的对应枚举对象，默认为 MsgRole.USER
     */
    private MsgRole parseRole(String role) {
        if (role == null) {
            return MsgRole.USER;
        }
        switch (role.toLowerCase()) {
            case "system":
                return MsgRole.SYSTEM;
            case "assistant":
                return MsgRole.ASSISTANT;
            case "user":
            default:
                return MsgRole.USER;
        }
    }

    /**
     * 重置缓存中共享 Agent 的短期上下文记忆，并填充从数据库计算出来、经过裁剪的最新会话历史上下文。
     * <p>以此规避由于缓存驻留和多会话共享导致的历史交叉污染，并使滑窗与摘要的记忆裁剪完全生效。</p>
     *
     * @param agent   目标加载的运行中 Agent 实例
     * @param history 从数据库读取并经过 MemoryManager 处理过的对话上下文历史列表
     */
    private void prepareAgentMemory(Agent agent, List<ChatMessage> history) {
        if (agent instanceof ReActAgent) {
            ReActAgent reactAgent = (ReActAgent) agent;
            if (reactAgent.getMemory() != null) {
                reactAgent.getMemory().clear();
                if (history != null) {
                    for (ChatMessage m : history) {
                        reactAgent.getMemory().addMessage(Msg.builder()
                                .role(parseRole(m.getRole()))
                                .textContent(m.getContent())
                                .build());
                    }
                }
            }
        }
    }
}
