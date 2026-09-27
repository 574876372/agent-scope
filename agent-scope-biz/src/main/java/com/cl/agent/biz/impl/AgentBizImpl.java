package com.cl.agent.biz.impl;

import com.cl.agent.tool.core.AgentToolkitFactory;
import com.cl.agent.biz.IAgentBiz;
import com.cl.agent.commons.UserContext;
import com.cl.agent.dto.AgentResponse;
import com.cl.agent.dto.ChatRequest;
import com.cl.agent.dto.ChatResponse;
import com.cl.agent.dto.CreateAgentRequest;
import com.cl.agent.biz.event.AgentCacheEvictEvent;
import com.cl.agent.biz.event.ModelConfigChangedEvent;
import com.cl.agent.service.IChatService;
import com.cl.agent.dto.model.ModelConnection;
import com.cl.agent.exception.BizException;
import com.cl.agent.model.AgentInfo;
import com.cl.agent.model.ChatMessage;
import com.cl.agent.service.IAgentService;
import com.cl.agent.service.IAgentToolRelService;
import com.cl.agent.service.IModelConfigService;
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
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import com.cl.agent.service.IKnowledgeService;
import com.cl.agent.biz.rag.EnhancedKnowledge;
import com.cl.agent.biz.rag.RagKnowledgeProvider;
import com.cl.agent.biz.rag.RetrievalPipeline;
import com.cl.agent.dto.rag.RetrievalOptions;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.RAGMode;
import io.agentscope.core.rag.model.RetrieveConfig;

import java.util.ArrayList;
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

    @Autowired
    private IKnowledgeService knowledgeService;

    @Autowired
    private RagKnowledgeProvider ragKnowledgeProvider;
    @Autowired
    private IModelConfigService modelConfigService;

    /** 会话数据服务，删除 Agent 时级联删除其会话 */
    @Autowired
    private IChatService chatService;

    /** 检索流水线，AGENTIC 模式的增强检索器由其驱动 */
    @Autowired
    private RetrievalPipeline retrievalPipeline;

    /** AGENTIC 检索工具在智能体未配置段数时的默认段数 */
    private static final int DEFAULT_TOOL_TOP_K = 5;

    /** 持久化到助手消息中的检索来源块，送入模型记忆前需去除 */
    private static final java.util.regex.Pattern RETRIEVAL_BLOCK = java.util.regex.Pattern.compile("(?s)<retrieval>.*?</retrieval>");


    /** 运行时 Agent 实例缓存 (不持久化，仅存放在内存中) */
    private final ConcurrentHashMap<String, Agent> agentInstanceCache = new ConcurrentHashMap<>();

    /**
     * 模型配置（厂商密钥、地址、模型参数）变更后清空运行时 Agent 缓存。
     * <p>缓存的 Agent 持有构建时的对话模型与知识库客户端，清空后下次对话按最新配置重建。</p>
     *
     * @param event 模型配置变更事件
     */
    @EventListener
    public void onModelConfigChanged(ModelConfigChangedEvent event) {
        int size = agentInstanceCache.size();
        agentInstanceCache.clear();
        log.info("[Agent] 模型配置已变更，清空运行时 Agent 缓存: count={}", size);
    }

    /**
     * 按 Agent ID 精确失效运行时缓存。
     * <p>使用说明：知识库删除或检索配置变更后由发布方同步触发；被移除的 Agent 下次对话时按最新知识库绑定重建，
     * 不会继续查询已删除的知识库。</p>
     *
     * @param event 缓存失效事件，携带需要失效的 Agent ID 列表
     * @return 无返回值；方法返回时对应缓存已移除
     */
    @EventListener
    public void onAgentCacheEvict(AgentCacheEvictEvent event) {
        if (event.getAgentIds().isEmpty()) {
            return;
        }
        event.getAgentIds().forEach(agentInstanceCache::remove);
        log.info("[Agent] 运行时 Agent 缓存已失效: agentIds={}, reason={}", event.getAgentIds(), event.getReason());
    }

    /**
     * 创建并持久化一个新的 Agent 实例。
     * <p>根据配置动态构建对应的 `ReActAgent`，并将其缓存至内存，同时把基本配置和关联工具记录到数据库中。</p>
     *
     * @param request 创建 Agent 的请求参数体，包含名称、模型厂商、具体模型、人设 Prompt、授权工具以及记忆模式和限制轮数等信息
     * @return AgentResponse 创建成功的 Agent 详细配置信息响应对象
     */
    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public AgentResponse createAgent(CreateAgentRequest request) {
        // 生成 Agent ID
        String agentId = UUID.randomUUID().toString();

        // 1. 持久化 Agent 基本信息（包含 RAG 与记忆参数）
        AgentInfo info = new AgentInfo();
        info.setId(agentId);
        info.setName(request.getName());
        info.setModelType(request.getModelType());
        info.setModelName(request.getModelName());
        info.setSystemPrompt(request.getSystemPrompt());
        info.setStatus("active");
        info.setUserId(UserContext.getUserId());
        // 记忆管理配置：memoryMode / maxTurns
        info.setMemoryMode(request.getMemoryMode());
        info.setMaxTurns(request.getMaxTurns());
        // RAG 检索参数绑定配置
        info.setRagMode(request.getRagMode() != null ? request.getRagMode() : "DISABLED");
        applyRetrievalConfig(info, request);
        agentService.save(info);

        // 2. 保存 Agent-工具关联关系
        if (request.getToolNames() != null && !request.getToolNames().isEmpty()) {
            agentToolRelService.replaceToolsForAgent(agentId, request.getToolNames());
        }

        // 3. 保存 Agent-知识库授权映射关联
        if (request.getKbIds() != null && !request.getKbIds().isEmpty()) {
            knowledgeService.replaceKbsForAgent(agentId, request.getKbIds());
        }

        // 4. 调用原生构建方法构建 Agent 实例（使用请求中指定的工具列表与 RAG 原生注入）
        Agent agent = buildAgent(
                request.getName(),
                request.getModelType(),
                request.getModelName(),
                request.getSystemPrompt(),
                request.getToolNames(),
                agentId,
                info
        );

        // 5. 缓存运行时实例到进程内存缓存中
        agentInstanceCache.put(agentId, agent);

        log.info("成功创建 Agent: ID={}, 名称={}, 工具={}, 绑定知识库={}", 
                agentId, info.getName(), request.getToolNames(), request.getKbIds());
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
     * 删除指定 ID 的 Agent，并级联清理其工具关联、知识库绑定、全部会话与内存缓存。
     * <p>使用说明：会话随 Agent 一并删除（其消息与摘要同时逻辑删除），避免残留无法继续对话的会话。</p>
     *
     * @param id 待删除 Agent 唯一标识符 ID，非空
     * @return 无返回值；方法返回时数据库与缓存均已清理
     */
    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public void deleteAgent(String id) {
        agentService.deleteById(id);
        agentToolRelService.deleteByAgentId(id);
        knowledgeService.deleteBindsByAgentId(id);
        int convCount = chatService.deleteByAgentId(id);
        agentInstanceCache.remove(id);
        log.info("删除 Agent: ID={}，已级联清理工具关联、知识库绑定与 {} 个会话", id, convCount);
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
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
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
        // 允许更新 RAG 配置：携带 ragMode 时视为完整的检索配置，各项为空表示恢复全局默认
        if (request.getRagMode() != null) {
            info.setRagMode(request.getRagMode());
            applyRetrievalConfig(info, request);
        }
        agentService.save(info);

        // 更新工具关联
        if (request.getToolNames() != null) {
            agentToolRelService.replaceToolsForAgent(id, request.getToolNames());
        }

        // 更新知识库授权映射关联
        if (request.getKbIds() != null) {
            knowledgeService.replaceKbsForAgent(id, request.getKbIds());
        }

        // 清除缓存，下次对话时会重建 Agent 实例（使用新的工具与知识库集）
        agentInstanceCache.remove(id);

        log.info("更新 Agent: ID={}, 名称={}, 工具={}, 绑定知识库={}", 
                id, info.getName(), request.getToolNames(), request.getKbIds());
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
            reactor.core.publisher.Mono<Msg> callMono = agent.call(buildInputMessages(request));
            if (userId != null) {
                callMono = callMono.contextWrite(context -> context.put("userId", userId));
            }
            reply = callMono.block();
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

        List<Msg> inputMessages = buildInputMessages(request);

        StreamOptions options = StreamOptions.builder()
                .eventTypes(EventType.REASONING, EventType.TOOL_RESULT, EventType.AGENT_RESULT)
                .incremental(true)
                .includeReasoningChunk(true)
                .includeReasoningResult(false)
                .build();

        AtomicLong startMs = new AtomicLong();
        AtomicBoolean firstEvent = new AtomicBoolean(true);

        String userId = UserContext.getUserId();
        reactor.core.publisher.Flux<Event> stream = agent.stream(inputMessages, options)
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
        if (userId != null) {
            stream = stream.contextWrite(context -> context.put("userId", userId));
        }
        return stream;
    }

    /**
     * 获取指定 Agent 的缓存实例，如果缓存中不存在，则查询数据库的元配置重建 Agent 并将其加入缓存中。
     *
     * @param id   Agent 唯一标识符 ID
     * @param info 数据库中缓存的 Agent 基础配置实体
     * @return Agent 缓存或重建后的运行时智能体实例对象
     */
    /**
     * 获取指定 Agent 的缓存实例，如果缓存中不存在，则查询数据库的元配置重建 Agent 并将其加入缓存中。
     * <p>使用说明：由对话等核心控制流触发以解析运行时 Agent 实例。</p>
     *
     * @param id   Agent 唯一标识符 ID，非空
     * @param info 数据库中缓存的 Agent 基础配置实体，非空
     * @return {@link Agent} 缓存或重建后的运行时智能体实例对象
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
                    toolNames,
                    id,
                    info
            );
            agentInstanceCache.put(id, agent);
        }
        return agent;
    }

    /**
     * 统一构建 ReActAgent 运行时对象。
     * <p>根据模型和 Prompt 信息创建模型实例，并绑定授权的工具包。若绑定了知识库且开启了 RAG，
     * 会为每个绑定的知识库装配一个直连向量库（ES / Milvus / 缓存内存库）的 Knowledge，并调用官方原生 Builder 绑定 knowledges 与 RAGMode。
     * 若 Studio 可视化已开启，还会自动挂载 Studio 推理过程追踪 Hook。</p>
     *
     * @param name       Agent 的友好显示名称，非空
     * @param modelType  模型提供商标识，非空
     * @param modelName  目标调用的模型名称，非空
     * @param sysPrompt  系统提示词（人设设定），非空
     * @param toolNames  授权关联的工具名称列表，可为空
     * @param agentId    智能体 ID，必填
     * @param info       智能体基础实体配置，必填
     * @return {@link Agent} 构建好的运行时 ReActAgent 实例
     */
    private Agent buildAgent(String name, String modelType, String modelName, String sysPrompt,
                             List<String> toolNames, String agentId, AgentInfo info) {
        OpenAIChatModel model = buildModel(modelType, modelName);
        ReActAgent.Builder builder = ReActAgent.builder()
                .name(name)
                .model(model)
                .sysPrompt(sysPrompt);

        // ==== 官方 RAG 集成规范自动注入 ====
        loadRagKnowledge(builder, agentId, info, name);

        // ==== 官方 工具 集成 ====
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
     * 为 AGENTIC 模式的 Agent 注入增强检索器。
     * <p>使用说明：构建 Agent 时调用。AGENTIC 模式向 {@code ReActAgent.Builder#knowledges} 只传入一个
     * {@link EnhancedKnowledge}（覆盖全部绑定知识库），框架将其包装为 {@code retrieve_knowledge} 工具，工具结果带来源标注。
     * GENERIC 模式不再使用框架的 GenericRAGHook：检索由 {@code ChatBizImpl} 在调用 Agent 前完成并推送引用来源，
     * 因此这里不注入任何知识库。单个知识库不可用（如缺 API Key）不会影响构建，检索时由流水线跳过并告警。</p>
     *
     * @param builder   ReActAgent 构建器，非空
     * @param agentId   智能体 ID，非空
     * @param info      智能体配置，非空
     * @param agentName 智能体名称，用于日志
     * @return 无返回值；直接修改 builder
     */
    private void loadRagKnowledge(ReActAgent.Builder builder, String agentId, AgentInfo info, String agentName) {
        if (!ragKnowledgeProvider.isEnabled() || agentId == null || !"AGENTIC".equalsIgnoreCase(info.getRagMode())) {
            return;
        }
        List<String> kbIds = knowledgeService.getKbIdsByAgentId(agentId);
        if (kbIds.isEmpty()) {
            return;
        }
        RetrievalOptions template = retrievalPipeline.agentOptions(info, kbIds);
        Knowledge knowledge = new EnhancedKnowledge(retrievalPipeline, template);
        builder.knowledges(List.of(knowledge))
               .ragMode(RAGMode.AGENTIC)
               // 框架要求非空配置；实际段数、阈值由 EnhancedKnowledge 按智能体配置决定
               .retrieveConfig(RetrieveConfig.builder()
                       .limit(template.getFinalTopK() != null ? template.getFinalTopK() : DEFAULT_TOOL_TOP_K)
                       .scoreThreshold(0.0)
                       .build());
        log.info("[RAG-Build] Agent [{}] 已注入增强检索工具: kbIds={}", agentName, kbIds);
    }

    /**
     * 根据模型厂商和名称构建 OpenAI 兼容协议大模型客户端。
     * <p>此处强制底层使用 HTTP/1.1 以规避 HTTP/2 在 SSE 长连接断开或复用时的部分不稳定问题，同时使用更具弹性的 OkHttp 传输层。</p>
     *
     * @param modelType 模型厂商编码，对应 t_model_provider.code
     * @param modelName 具体的模型名称
     * @return OpenAIChatModel 实例化完成的 LLM 客户端
     */
    private OpenAIChatModel buildModel(String modelType, String modelName) {
        // 接口地址与密钥从模型配置表读取（密钥解密后仅在内存中使用）
        ModelConnection conn = modelConfigService.resolveChatConnection(modelType, modelName);

        // 强制在客户端使用 HTTP/1.1 协议
        // 目的：防止 JDK HttpClient/OkHttp 用默认 HTTP/2 协议请求 DeepSeek/通义等接口时，
        // 在 SSE（流式）结束或连接复用时由于代理或网关发送的 RST_STREAM / 提前断开，
        // 导致抛出 "okhttp3.internal.http2.StreamResetException: stream was reset: CANCEL"
        // 或 "java.io.IOException: closed" / "EOFReachedException" 异常。
        HttpTransportConfig config = HttpTransportConfig.builder()
                .httpVersion(HttpVersion.HTTP_1_1)
                .build();
                
        // 显式构建 OkHttpClient 并强制设定协议为 HTTP/1.1 (因为 OkHttpTransport 默认会忽略 HttpTransportConfig.httpVersion)
        okhttp3.OkHttpClient.Builder clientBuilder = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(config.getConnectTimeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .readTimeout(config.getReadTimeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .writeTimeout(config.getWriteTimeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .connectionPool(new okhttp3.ConnectionPool(
                        config.getMaxIdleConnections(),
                        config.getKeepAliveDuration().toMillis(),
                        java.util.concurrent.TimeUnit.MILLISECONDS
                ))
                .protocols(java.util.List.of(okhttp3.Protocol.HTTP_1_1));

        // 兼容忽略 SSL 证书校验的配置
        if (config.isIgnoreSsl()) {
            try {
                javax.net.ssl.TrustManager[] trustAllCerts = new javax.net.ssl.TrustManager[]{
                    new javax.net.ssl.X509TrustManager() {
                        @Override
                        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                        @Override
                        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                        @Override
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[]{};
                        }
                    }
                };
                javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("SSL");
                sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
                clientBuilder.sslSocketFactory(sslContext.getSocketFactory(), (javax.net.ssl.X509TrustManager) trustAllCerts[0]);
                clientBuilder.hostnameVerifier((hostname, session) -> true);
            } catch (Exception e) {
                log.error("Failed to configure trust-all SSL factory", e);
            }
        }

        // 兼容代理配置
        io.agentscope.core.model.transport.ProxyConfig proxyConfig = config.getProxyConfig();
        if (proxyConfig != null) {
            if (proxyConfig.getNonProxyHosts() != null && !proxyConfig.getNonProxyHosts().isEmpty()) {
                clientBuilder.proxySelector(new java.net.ProxySelector() {
                    @Override
                    public java.util.List<java.net.Proxy> select(java.net.URI uri) {
                        if (proxyConfig.getNonProxyHosts().contains(uri.getHost())) {
                            return java.util.List.of(java.net.Proxy.NO_PROXY);
                        }
                        return java.util.List.of(proxyConfig.toJavaProxy());
                    }
                    @Override
                    public void connectFailed(java.net.URI uri, java.net.SocketAddress sa, java.io.IOException ioe) {}
                });
            } else {
                clientBuilder.proxy(proxyConfig.toJavaProxy());
            }

            if (proxyConfig.hasAuthentication()) {
                clientBuilder.proxyAuthenticator((route, response) -> {
                    String credential = okhttp3.Credentials.basic(proxyConfig.getUsername(), proxyConfig.getPassword());
                    return response.request().newBuilder()
                            .header("Proxy-Authorization", credential)
                            .build();
                });
            }
        }

        okhttp3.OkHttpClient okHttpClient = clientBuilder.build();

        // 使用 OkHttp 传输层实例替代 JDK HttpClient，并注入我们自定义的 HTTP/1.1 OkHttpClient
        HttpTransport transport = OkHttpTransport.builder()
                .client(okHttpClient)
                .config(config)
                .build();
                
        return OpenAIChatModel.builder()
                .baseUrl(conn.getBaseUrl())
                .apiKey(conn.getApiKey())
                .modelName(conn.getModelName())
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
        // 回填 RAG 配置供前端展示和编辑时回显
        resp.setRagMode(info.getRagMode());
        resp.setRecallLimit(info.getRecallLimit());
        resp.setScoreThreshold(info.getScoreThreshold());
        resp.setQueryRewrite(info.getQueryRewrite());
        resp.setContextMaxChars(info.getContextMaxChars());
        resp.setRerankModelId(info.getRerankModelId());
        if (knowledgeService != null) {
            resp.setKbIds(knowledgeService.getKbIdsByAgentId(info.getId()));
        }
        return resp;
    }

    /**
     * 校验并写入智能体级检索参数；各项为空表示使用 {@code agent.rag.retrieval.*} 全局默认。
     *
     * @param info    目标实体，非空
     * @param request 创建 / 更新请求，非空
     * @return 无返回值；直接修改 info
     * @throws BizException 数值超出允许范围时抛出，code=400
     */
    private void applyRetrievalConfig(AgentInfo info, CreateAgentRequest request) {
        if (request.getRecallLimit() != null && (request.getRecallLimit() < 1 || request.getRecallLimit() > 20)) {
            throw new BizException(400, "最终段数须在 1 ~ 20 之间");
        }
        if (request.getScoreThreshold() != null && (request.getScoreThreshold() < 0 || request.getScoreThreshold() > 1)) {
            throw new BizException(400, "向量预过滤阈值须在 0 ~ 1 之间");
        }
        if (request.getContextMaxChars() != null && (request.getContextMaxChars() < 500 || request.getContextMaxChars() > 100000)) {
            throw new BizException(400, "上下文总长上限须在 500 ~ 100000 之间");
        }
        info.setRecallLimit(request.getRecallLimit());
        info.setScoreThreshold(request.getScoreThreshold());
        info.setQueryRewrite(request.getQueryRewrite());
        info.setContextMaxChars(request.getContextMaxChars());
        String rerankModelId = request.getRerankModelId();
        info.setRerankModelId(rerankModelId == null || rerankModelId.isBlank() ? null : rerankModelId.trim());
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
                        // 助手消息中持久化的检索来源块只用于界面展示，不送入模型，避免重复占用上下文
                        String content = m.getContent() == null ? "" : RETRIEVAL_BLOCK.matcher(m.getContent()).replaceAll("");
                        reactAgent.getMemory().addMessage(Msg.builder()
                                .role(parseRole(m.getRole()))
                                .textContent(content)
                                .build());
                    }
                }
            }
        }
    }

    /**
     * 组装本轮发给 Agent 的输入消息：用户问题，以及 GENERIC 前置检索得到的知识库上下文（如有）。
     * <p>知识库上下文作为独立的用户消息紧跟在问题之后（与框架 GenericRAGHook 的注入位置一致），
     * 只存在于本轮调用中，不写入会话历史。</p>
     *
     * @param request 对话请求，{@code content} 必填，{@code knowledgeContext} 可为空
     * @return 输入消息列表，至少包含用户问题
     */
    private List<Msg> buildInputMessages(ChatRequest request) {
        List<Msg> messages = new ArrayList<>(2);
        messages.add(Msg.builder()
                .textContent(request.getContent())
                .role(MsgRole.USER)
                .build());
        if (request.getKnowledgeContext() != null && !request.getKnowledgeContext().isBlank()) {
            messages.add(Msg.builder()
                    .name("user")
                    .textContent(request.getKnowledgeContext())
                    .role(MsgRole.USER)
                    .build());
        }
        return messages;
    }
}
