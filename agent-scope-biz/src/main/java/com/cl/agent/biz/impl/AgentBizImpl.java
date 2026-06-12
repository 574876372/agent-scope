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
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.studio.StudioManager;
import io.agentscope.core.studio.StudioMessageHook;
import io.agentscope.core.tool.Toolkit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import com.cl.agent.service.IKnowledgeService;
import com.cl.agent.rag.core.EmbeddingStoreFactory;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.RAGMode;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.rag.store.InMemoryStore;
import com.cl.agent.model.KnowledgeChunk;

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

    @Autowired(required = false)
    private EmbeddingStoreFactory embeddingStoreFactory;


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
        info.setRecallLimit(request.getRecallLimit());
        info.setScoreThreshold(request.getScoreThreshold());
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
        // 允许更新 RAG 配置
        if (request.getRagMode() != null) {
            info.setRagMode(request.getRagMode());
        }
        if (request.getRecallLimit() != null) {
            info.setRecallLimit(request.getRecallLimit());
        }
        if (request.getScoreThreshold() != null) {
            info.setScoreThreshold(request.getScoreThreshold());
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
     * 会自动动态在内存中重构向量索引数据库（实现多库动态预热支持），并调用官方原生 Builder 绑定 SimpleKnowledge 实例与 RAGMode。
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
     * 加载 RAG 知识库并注入 Agent 构建器。
     *
     * @param builder   ReActAgent 构建器
     * @param agentId   智能体 ID
     * @param info      智能体基本配置实体
     * @param agentName 智能体友好名称
     */
    private void loadRagKnowledge(ReActAgent.Builder builder, String agentId, AgentInfo info, String agentName) {
        if (knowledgeService != null && embeddingStoreFactory != null && agentId != null) {
            List<String> kbIds = knowledgeService.getKbIdsByAgentId(agentId);
            String modeStr = info.getRagMode() != null ? info.getRagMode() : "DISABLED";
            
            if (!kbIds.isEmpty() && !"DISABLED".equalsIgnoreCase(modeStr)) {
                log.info("[RAG-Build] 检测到 Agent [{}] 开启并绑定知识库: kbIds={}, mode={}", agentName, kbIds, modeStr);
                
                // 1. 获取模型配置生成 Embedding Model 实例
                ModelProviderEnum provider = ModelProviderEnum.QWEN;
                EmbeddingModel embeddingModel = embeddingStoreFactory.createEmbeddingModel(provider.getApiKey(), provider.getBaseUrl());
                
                // 2. 构造一个专用于该 Agent 运行时检索的内存型向量存储实例，以支持灵活的多知识库聚合检索
                InMemoryStore runtimeStore = InMemoryStore.builder().dimensions(1536).build();
                SimpleKnowledge knowledge = SimpleKnowledge.builder()
                        .embeddingModel(embeddingModel)
                        .embeddingStore(runtimeStore)
                        .build();
                
                // 3. 从 MySQL 中加载所有绑定知识库的已索引切片数据进行动态“预热注入”
                List<Document> preheatDocs = new ArrayList<>();
                for (String kbId : kbIds) {
                    List<KnowledgeChunk> chunks = knowledgeService.listChunksByKbId(kbId);
                    for (KnowledgeChunk chunk : chunks) {
                        DocumentMetadata meta = DocumentMetadata.builder()
                                .docId(chunk.getDocId())
                                .chunkId(String.valueOf(chunk.getChunkIndex()))
                                .content(io.agentscope.core.message.TextBlock.builder().text(chunk.getContent()).build())
                                .build();
                        preheatDocs.add(new Document(meta));
                    }
                }
                
                if (!preheatDocs.isEmpty()) {
                    // 向量化加载入内存
                    knowledge.addDocuments(preheatDocs).block();
                    log.info("[RAG-Build] 成功向 Agent 运行时向量库预热加载切片数={}", preheatDocs.size());
                }
                
                // 4. 配置召回限制和得分过滤阈值
                int limit = info.getRecallLimit() != null ? info.getRecallLimit() : 3;
                double threshold = info.getScoreThreshold() != null ? info.getScoreThreshold() : 0.3;
                
                // 5. 注入 AgentScope 官方原生支持的属性
                builder.knowledge(knowledge)
                       .ragMode(RAGMode.valueOf(modeStr))
                       .retrieveConfig(RetrieveConfig.builder()
                               .limit(limit)
                               .scoreThreshold(threshold)
                               .build());
                log.info("[RAG-Build] RAG 配置装配完成: limit={}, threshold={}", limit, threshold);
            }
        }
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
        // 回填 RAG 配置供前端展示和编辑时回显
        resp.setRagMode(info.getRagMode());
        resp.setRecallLimit(info.getRecallLimit());
        resp.setScoreThreshold(info.getScoreThreshold());
        if (knowledgeService != null) {
            resp.setKbIds(knowledgeService.getKbIdsByAgentId(info.getId()));
        }
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
