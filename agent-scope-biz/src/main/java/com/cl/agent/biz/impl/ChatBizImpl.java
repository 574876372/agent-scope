package com.cl.agent.biz.impl;

import com.cl.agent.biz.IAgentBiz;
import com.cl.agent.biz.IChatBiz;
import com.cl.agent.biz.IGenericHitlBiz;
import com.alibaba.fastjson2.JSONObject;
import com.cl.agent.biz.memory.MemoryManager;
import com.cl.agent.biz.rag.RetrievalPipeline;
import com.cl.agent.dto.rag.RetrievalOptions;
import com.cl.agent.dto.rag.RetrievalTrace;
import com.cl.agent.service.IKnowledgeService;
import com.cl.agent.commons.UserContext;
import com.cl.agent.dto.*;
import com.cl.agent.model.AgentInfo;
import com.cl.agent.service.IAgentService;
import com.cl.agent.stream.StreamAccumulator;
import com.cl.agent.stream.StreamContext;
import com.cl.agent.exception.BizException;
import com.cl.agent.model.ChatMessage;
import com.cl.agent.model.Conversation;
import com.cl.agent.service.IChatService;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 对话业务实现：流式接口将 AgentScope Event 映射为 SSE（reasoning / tool_result / message）。
 */
@Service
@Slf4j
public class ChatBizImpl implements IChatBiz {

    /** 未关联 Agent 时返回给前端的默认占位回复 */
    private static final String PLACEHOLDER_NO_AGENT = "AI 暂时无法响应，请关联 Agent。";

    /**
     * 用于从工具结果 JSON 中提取 tool 名称和 output 内容的正则表达式。
     * 匹配格式：{"tool":"...","output":"..."}，output 支持转义字符。
     */
    private static final Pattern TOOL_JSON_PATTERN = Pattern
            .compile("\"tool\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"output\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** 会话数据访问服务，负责会话的增删改查持久化操作 */
    @Autowired
    private IChatService chatService;

    /** Agent 业务接口，用于向指定 Agent 发送消息（同步/流式） */
    @Autowired
    private IAgentBiz agentBiz;

    /** Agent 数据访问服务，用于查询 Agent 配置（含 memoryMode / maxTurns） */
    @Autowired
    private IAgentService agentService;

    /** 记忆管理器，根据 Agent 配置处理上下文（FULL / WINDOW / SUMMARY） */
    @Autowired
    private MemoryManager memoryManager;

    /** 通用 HITL 业务接口，仅在 hitlAction 非空的请求中被拦截调用 */
    @Autowired
    private IGenericHitlBiz genericHitlBiz;

    /** 检索流水线，GENERIC 模式在调用 Agent 前由此完成检索 */
    @Autowired
    private RetrievalPipeline retrievalPipeline;

    /** 知识库服务，查询 Agent 绑定的知识库 */
    @Autowired
    private IKnowledgeService knowledgeService;

    @Override
    public ConversationResponse createConversation(CreateConversationRequest request) {
        Conversation conv = new Conversation();
        conv.setId(UUID.randomUUID().toString());
        conv.setTitle(request.getTitle() != null ? request.getTitle() : "新对话");
        conv.setAgentId(request.getAgentId());
        conv.setMessages(new ArrayList<>());
        conv.setUserId(UserContext.getUserId());

        chatService.save(conv);
        return toConversationResponse(conv);
    }

    @Override
    public List<ConversationResponse> listConversations() {
        String userId = UserContext.getUserId();
        return chatService.listByUserId(userId).stream()
                .map(this::toConversationResponse)
                .collect(Collectors.toList());
    }

    @Override
    public void deleteConversation(String conversationId) {
        chatService.deleteById(conversationId);
    }

    @Override
    public SendMessageResponse sendMessage(SendMessageRequest request) {
        String conversationId = request.getConversationId();
        if (conversationId == null || conversationId.trim().isEmpty() || "undefined".equals(conversationId)) {
            CreateConversationRequest createReq = new CreateConversationRequest();
            createReq.setTitle(generateTitle(request.getContent()));
            conversationId = createConversation(createReq).getId();
        }

        Conversation conv = chatService.getById(conversationId);
        if (conv == null) {
            throw new BizException(404, "会话不存在: " + conversationId);
        }
        if (conv.getAgentId() != null) {
            requireAgentOfConversation(conv);
        }

        LocalDateTime now = LocalDateTime.now();
        ChatMessage userMsg = new ChatMessage();
        userMsg.setRole("user");
        userMsg.setContent(request.getContent());
        userMsg.setTimestamp(now);
        conv.getMessages().add(userMsg);

        String aiContent = PLACEHOLDER_NO_AGENT;
        if (conv.getAgentId() != null) {
            AgentInfo agentInfo = agentService.getById(conv.getAgentId());
            List<ChatMessage> contextMessages = memoryManager.resolveContext(conv, agentInfo);
            log.debug("[Memory] 同步上下文已处理, conversationId={}, contextSize={}",
                    conversationId, contextMessages.size());

            ChatRequest chatRequest = new ChatRequest();
            chatRequest.setContent(request.getContent());
            chatRequest.setHistory(contextMessages);
            // GENERIC 模式前置检索：上下文交给 Agent，来源随回复一起持久化
            RetrievalTrace trace = retrieveForGeneric(conv, request.getContent());
            String retrievalBlock = "";
            if (trace != null) {
                chatRequest.setKnowledgeContext(trace.getContextText());
                if (!trace.getSegments().isEmpty() || !trace.getWarnings().isEmpty()) {
                    retrievalBlock = StreamAccumulator.wrapRetrieval(toRetrievalJson(trace));
                }
            }
            ChatResponse chatResponse = agentBiz.chat(conv.getAgentId(), chatRequest);
            aiContent = retrievalBlock + chatResponse.getContent();
        }

        ChatMessage aiMsg = new ChatMessage();
        aiMsg.setRole("assistant");
        aiMsg.setContent(aiContent);
        aiMsg.setTimestamp(LocalDateTime.now());
        conv.getMessages().add(aiMsg);
        conv.setUpdateTime(LocalDateTime.now());

        chatService.save(conv);

        SendMessageResponse resp = new SendMessageResponse();
        resp.setConversationId(conversationId);
        resp.setUserMessage(request.getContent());
        resp.setContent(aiContent);
        resp.setTimestamp(now);
        return resp;
    }

    @Override
    public Flux<ChatStreamEvent> sendMessageStream(SendMessageRequest request) {
        if (request.getHitlAction() != null) {
            return genericHitlBiz.confirmExecution(request);
        }
        //处理会话id  如果会话不存在则创建新的会话
        String resolvedConversationId = resolveConversationId(request);
        try {
            StreamContext ctx = prepareStreamContext(resolvedConversationId, request);
            return buildAgentStreamFlux(ctx);
        } catch (Exception e) {
            log.error("[Flux] 流式消息推送异常", e);
            String msg = e.getMessage() != null ? e.getMessage() : "流式对话失败";
            return Flux.just(new ChatStreamEvent("error", msg));
        }
    }

    /**
     * 解析或创建会话 ID。
     */
    private String resolveConversationId(SendMessageRequest request) {
        String rawConvId = request.getConversationId();
        if (rawConvId == null || rawConvId.trim().isEmpty() || "undefined".equals(rawConvId)) {
            CreateConversationRequest createReq = new CreateConversationRequest();
            createReq.setTitle(generateTitle(request.getContent()));
            return createConversation(createReq).getId();
        }
        return rawConvId;
    }

    /**
     * 加载会话并追加用户消息，同时通过 {@link MemoryManager} 处理历史上下文。
     */
    private StreamContext prepareStreamContext(String conversationId, SendMessageRequest request) {
        // 根据会话 ID 从数据库加载完整的会话对象（含历史消息）
        Conversation conv = chatService.getById(conversationId);
        if (conv == null) {
            throw new BizException(404, "会话不存在: " + conversationId);
        }
        // 关联的 Agent 已被删除时直接拒绝，且不落库本次提问，避免产生无法回复的孤立消息
        AgentInfo agentInfo = conv.getAgentId() != null ? requireAgentOfConversation(conv) : null;

        // 记录当前时间作为用户消息的时间戳，保证同一请求中时间一致
        LocalDateTime now = LocalDateTime.now();

        // 构建用户消息实体并写入会话的消息列表
        ChatMessage userMsg = new ChatMessage();
        userMsg.setRole("user");
        userMsg.setContent(request.getContent());
        userMsg.setTimestamp(now);
        conv.getMessages().add(userMsg);

        // 提前持久化用户消息：即使后续 Agent 调用失败，用户的提问也不会丢失
        chatService.save(conv);

        // 通过 MemoryManager 处理历史上下文（FULL / WINDOW / SUMMARY 三路分支）
        List<ChatMessage> contextMessages = null;
        if (agentInfo != null) {
            contextMessages = memoryManager.resolveContext(conv, agentInfo);
            log.debug("[Memory] 上下文已处理, conversationId={}, contextSize={}",
                    conversationId, contextMessages.size());
        }

        return new StreamContext(conv, conversationId, request.getContent(), contextMessages);
    }

    /**
     * 构建完整 SSE 流：控制事件 + Agent 事件 + 持久化 + [DONE]。
     */
    private Flux<ChatStreamEvent> buildAgentStreamFlux(StreamContext ctx) {
        // Flux.create 创建一个可手动控制的响应式数据流。
        // sink 就是「水龙头开关」：调用 sink.next() 向前端推一条数据，
        // 调用 sink.complete() 关闭连接，整个 SSE 生命周期都由 sink 控制。
        return Flux.create(sink -> {
            try {
                // ── 第 1 步：立即推送会话 ID 控制帧 ───────────────────────────────
                // 前端连接后第一件事：告知会话 ID。
                // 对于新建会话，前端发请求时还不知道 ID，通过这一帧获取后
                // 可以更新浏览器 URL，后续消息也能正确归属到该会话。
                sink.next(new ChatStreamEvent(null, "[CONV_ID]" + ctx.getConversationId()));

                // ── 第 2 步：检查是否绑定了 Agent ────────────────────────────────
                if (ctx.getConversation().getAgentId() == null) {
                    // 没有关联 Agent，无法进行 AI 对话，推送占位提示给前端
                    sink.next(messageEvent(PLACEHOLDER_NO_AGENT));
                    // 将占位消息持久化，保证历史记录完整
                    persistAssistantMessage(ctx.getConversation(), PLACEHOLDER_NO_AGENT);
                    // 推送结束帧，前端据此判断本次 SSE 流已结束
                    sink.next(doneEvent());
                    // 关闭 Flux 流，释放连接资源（不关闭会一直挂起）
                    sink.complete();
                    return; // 提前返回，不再执行后续 Agent 调用逻辑
                }

                // ── 第 3 步：构造对 Agent 的流式请求 ─────────────────────────────
                ChatRequest chatRequest = new ChatRequest();
                chatRequest.setContent(ctx.getUserContent()); // 将用户本轮输入传给 Agent
                chatRequest.setHistory(ctx.getContextMessages()); // 将历史上下文传给 Agent

                // 创建累积器：边接收 Agent 的碎片事件，边拼装完整文本，
                // 最终在流结束后一次性存入数据库，避免存到一半数据不完整
                StreamAccumulator accumulator = new StreamAccumulator();

                // ── 第 3.5 步：GENERIC 模式前置检索，推送引用来源并把检索上下文交给 Agent ──
                RetrievalTrace trace = retrieveForGeneric(ctx.getConversation(), ctx.getUserContent());
                if (trace != null) {
                    chatRequest.setKnowledgeContext(trace.getContextText());
                    if (!trace.getSegments().isEmpty() || !trace.getWarnings().isEmpty()) {
                        String json = toRetrievalJson(trace);
                        accumulator.setRetrieval(json);
                        sink.next(new ChatStreamEvent("retrieval", json));
                    }
                }

                // ── 第 4 步：订阅 Agent 流式事件，注册三个回调 ───────────────────
                agentBiz.chatStream(ctx.getConversation().getAgentId(), chatRequest).subscribe(
                        // 回调 A（onNext）：每当 Agent 推来一个事件片段时执行。
                        // mapAgentEventToSse 将 Agent 事件转为前端可识别的 SSE 格式，
                        // 然后立即通过 sink.next() 推给前端，实现「打字机」效果。
                        event -> sink.next(mapAgentEventToSse(event, accumulator)),

                        // 回调 B（onError）：Agent 流中途出错时执行。
                        // 记录日志后推送错误事件通知前端，再关闭流。
                        error -> {
                            log.error("[Flux] Agent 流式推送异常, conversationId={}, agentId={}",
                                    ctx.getConversationId(), ctx.getConversation().getAgentId(), error);
                            String msg = error.getMessage() != null ? error.getMessage() : "流式对话失败";
                            sink.next(new ChatStreamEvent("error", msg));
                            sink.complete(); // 出错也必须关闭流，否则连接泄漏
                        },

                        // 回调 C（onComplete）：Agent 所有事件推送完毕后执行。
                        // 此时 accumulator 已收集到完整内容，执行持久化并发送 [DONE]。
                        () -> finishAgentStream(ctx, accumulator, sink));
            } catch (Exception e) {
                // 兜底异常处理：捕获 Flux.create 内部同步代码（如构建请求）抛出的异常，
                // 推送错误事件给前端并关闭流，防止连接永久挂起
                log.error("[Flux] 流式消息推送异常", e);
                String msg = e.getMessage() != null ? e.getMessage() : "流式对话失败";
                sink.next(new ChatStreamEvent("error", msg));
                sink.complete();
            }
        });
    }

    /**
     * Agent 事件流结束后持久化助手消息并发送 [DONE]。
     */
    private void finishAgentStream(StreamContext ctx, StreamAccumulator accumulator, FluxSink<ChatStreamEvent> sink) {
        try {
            if (accumulator.hasNoModelOutput()) {
                accumulator.appendMessage(PLACEHOLDER_NO_AGENT);
            }
            String persistContent = accumulator.buildPersistContent();
            persistAssistantMessage(ctx.getConversation(), persistContent);
            sink.next(doneEvent());
            sink.complete();
        } catch (Exception e) {
            log.error("[Flux] 流式消息持久化异常", e);
            String msg = e.getMessage() != null ? e.getMessage() : "流式对话失败";
            sink.next(new ChatStreamEvent("error", msg));
            sink.complete();
        }
    }

    private ChatStreamEvent mapAgentEventToSse(Event event, StreamAccumulator accumulator) {
        // 获取事件类型（REASONING / TOOL_RESULT / AGENT_RESULT 等）
        EventType type = event.getType();
        // 从事件消息体中提取纯文本内容，消息为空时返回空字符串
        String text = extractEventText(event);

        if (type == EventType.REASONING) {
            // 推理过程：AI「思考中」产生的中间步骤文本（类似 DeepSeek 的思考链）。
            // 存入 accumulator，持久化时会包裹在 <think>...</think> 标签内。
            accumulator.appendReasoning(text);
            return new ChatStreamEvent("reasoning", text); // 前端可据此显示「思考气泡」
        }
        if (type == EventType.TOOL_RESULT) {
            // 工具调用结果：Agent 调用外部工具（如搜索、计算）后返回的结果。
            // 先序列化为 {"tool":"...","output":"..."} 格式的 JSON，
            // 便于前端解析并渲染工具调用卡片。
            String json = formatToolResultJson(event);
            accumulator.appendToolResultJson(json); // 存入 accumulator，持久化为可读摘要行
            return new ChatStreamEvent("tool_result", json);
        }
        if (type == EventType.AGENT_RESULT) {
            // Agent 最终回复：展示给用户的正式答案文本片段（可能分多帧推送）
            accumulator.appendMessage(text);
            return new ChatStreamEvent("message", text);
        }

        // 兜底：其他未明确处理的事件类型，统一当作普通消息推给前端
        accumulator.appendMessage(text);
        return messageEvent(text);
    }

    private static String extractEventText(Event event) {
        Msg msg = event.getMessage();
        if (msg == null) {
            return "";
        }
        String text = msg.getTextContent();
        return text != null ? text : "";
    }

    /**
     * 将工具结果事件序列化为前端可解析的 JSON。
     */
    private String formatToolResultJson(Event event) {
        Msg msg = event.getMessage();
        if (msg == null) {
            return "{\"tool\":\"tool\",\"output\":\"\"}";
        }
        String tool = "tool";
        String output = extractEventText(event);
        List<ToolResultBlock> blocks = msg.getContentBlocks(ToolResultBlock.class);
        if (!blocks.isEmpty()) {
            ToolResultBlock block = blocks.get(0);
            if (block.getName() != null && !block.getName().isBlank()) {
                tool = block.getName();
            }
            if (block.getOutput() != null) {
                StringBuilder sb = new StringBuilder();
                for (Object cb : block.getOutput()) {
                    if (cb != null) {
                        sb.append(cb.toString());
                    }
                }
                output = sb.toString();
            }
        }
        return "{\"tool\":\"" + jsonEscape(tool) + "\",\"output\":\"" + jsonEscape(output) + "\"}";
    }

    private static ChatStreamEvent messageEvent(String data) {
        return new ChatStreamEvent("message", data);
    }

    private static ChatStreamEvent doneEvent() {
        return new ChatStreamEvent(null, "[DONE]");
    }

    private void persistAssistantMessage(Conversation conv, String content) {
        ChatMessage aiMsg = new ChatMessage();
        aiMsg.setRole("assistant");
        aiMsg.setContent(content);
        aiMsg.setTimestamp(LocalDateTime.now());
        conv.getMessages().add(aiMsg);
        conv.setUpdateTime(LocalDateTime.now());
        chatService.save(conv);
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    @Override
    public List<ChatMessageResponse> getHistory(String conversationId) {
        Conversation conv = chatService.getById(conversationId);
        if (conv == null) {
            throw new BizException(404, "会话不存在: " + conversationId);
        }
        return conv.getMessages().stream()
                .map(this::toChatMessageResponse)
                .collect(Collectors.toList());
    }

    /**
     * GENERIC 模式前置检索：使用与演练场、AGENTIC 工具相同的检索流水线。
     * <p>使用说明：调用 Agent 前执行，同步阻塞（含 Embedding、查询改写等模型调用）。未启用 GENERIC 或未绑定知识库时返回 null；
     * 检索异常时记录日志并返回 null，对话照常进行（不带知识库上下文）。</p>
     *
     * @param conv     会话实体（含历史消息，最后一条为本轮用户消息），{@code agentId} 非空
     * @param question 用户本轮问题，非空
     * @return 检索过程记录；不需要检索或检索失败时返回 null
     */
    private RetrievalTrace retrieveForGeneric(Conversation conv, String question) {
        if (conv.getAgentId() == null || question == null || question.isBlank()) {
            return null;
        }
        AgentInfo info = agentService.getById(conv.getAgentId());
        if (info == null || !"GENERIC".equalsIgnoreCase(info.getRagMode())) {
            return null;
        }
        List<String> kbIds = knowledgeService.getKbIdsByAgentId(info.getId());
        if (kbIds.isEmpty()) {
            return null;
        }
        try {
            RetrievalOptions options = retrievalPipeline.agentOptions(info, kbIds);
            options.setQuery(question);
            options.setHistory(previousTurns(conv));
            return retrievalPipeline.run(options);
        } catch (Exception e) {
            log.error("[RAG-Generic] 前置检索失败，本轮不注入知识库上下文: conversationId={}, agentId={}",
                    conv.getId(), info.getId(), e);
            return null;
        }
    }

    /**
     * 取本轮之前的对话（user / assistant），供查询改写参考；本轮用户消息已追加在末尾，需排除。
     *
     * @param conv 会话实体，消息按时间升序
     * @return 元素为 {role, content} 的列表，按时间升序；无历史时为空列表
     */
    private static List<String[]> previousTurns(Conversation conv) {
        List<ChatMessage> messages = conv.getMessages() == null ? List.of() : conv.getMessages();
        int end = messages.size() - 1;
        List<String[]> history = new ArrayList<>();
        for (int i = 0; i < end; i++) {
            ChatMessage m = messages.get(i);
            if (("user".equals(m.getRole()) || "assistant".equals(m.getRole())) && m.getContent() != null) {
                history.add(new String[]{m.getRole(), m.getContent()});
            }
        }
        return history;
    }

    /**
     * 生成 retrieval 事件载荷：检索词与来源段，不含各阶段中间结果与完整上下文，控制推送与持久化体积。
     *
     * @param trace 检索过程记录，非空
     * @return JSON 字符串（单行）
     */
    private static String toRetrievalJson(RetrievalTrace trace) {
        JSONObject obj = new JSONObject();
        obj.put("query", trace.getQuery());
        obj.put("rewrittenQuery", trace.getRewrittenQuery());
        obj.put("rewriteApplied", trace.isRewriteApplied());
        obj.put("reranked", trace.isReranked());
        obj.put("segments", trace.getSegments());
        obj.put("warnings", trace.getWarnings());
        obj.put("costMs", trace.getCostMs());
        return obj.toJSONString();
    }

    /**
     * 查询会话关联的 Agent，不存在时抛出业务异常。
     * <p>使用说明：发送消息前调用；Agent 被删除后其会话通常已级联删除，此处兜底处理删除前遗留的旧会话。</p>
     *
     * @param conv 会话实体，{@code agentId} 非空
     * @return 会话关联的 Agent 配置实体，非空
     * @throws BizException Agent 已删除时抛出，code=410，前端据此提示用户新建对话
     */
    private AgentInfo requireAgentOfConversation(Conversation conv) {
        AgentInfo agentInfo = agentService.getById(conv.getAgentId());
        if (agentInfo == null) {
            throw new BizException(410, "该对话关联的智能体已被删除，无法继续对话，请新建对话");
        }
        return agentInfo;
    }

    private String generateTitle(String message) {
        return message.length() > 20 ? message.substring(0, 20) + "..." : message;
    }

    private ConversationResponse toConversationResponse(Conversation conv) {
        ConversationResponse resp = new ConversationResponse();
        resp.setId(conv.getId());
        resp.setTitle(conv.getTitle());
        resp.setAgentId(conv.getAgentId());
        resp.setCreateTime(conv.getCreateTime());
        resp.setUpdateTime(conv.getUpdateTime());
        return resp;
    }

    private ChatMessageResponse toChatMessageResponse(ChatMessage msg) {
        ChatMessageResponse resp = new ChatMessageResponse();
        resp.setRole(msg.getRole());
        resp.setContent(msg.getContent());
        resp.setTimestamp(msg.getTimestamp());
        return resp;
    }
}
