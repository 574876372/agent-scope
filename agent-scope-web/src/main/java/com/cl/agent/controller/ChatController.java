package com.cl.agent.controller;

import com.cl.agent.dto.*;
import com.cl.agent.biz.IChatBiz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Function;

/**
 * 对话管理 REST 控制器层。
 * <p>提供会话的创建、查询、删除，以及同步消息发送、SSE 流式消息发送和会话历史查询能力。
 * 所有接口均使用明确的固定路径，参数通过 {@code @RequestParam} 查询参数传递，
 * 禁止使用 {@code {pathVariable}} 动态路径变量方式传递业务 ID。
 * 遵循严格的层级边界规范，仅调用 {@link IChatBiz} 业务编排层。</p>
 *
 * <h2>路由一览</h2>
 * <ul>
 *   <li>{@code POST   /api/chat/conversation/create}  — 创建会话</li>
 *   <li>{@code GET    /api/chat/conversation/list}    — 获取所有会话</li>
 *   <li>{@code DELETE /api/chat/conversation/delete}  — 删除会话，?conversationId=</li>
 *   <li>{@code POST   /api/chat/message}              — 同步发送消息</li>
 *   <li>{@code POST   /api/chat/message/stream}       — SSE 流式发送消息</li>
 *   <li>{@code GET    /api/chat/history}              — 获取会话历史，?conversationId=</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*")
public class ChatController {

    @Autowired
    private IChatBiz chatBiz;

    /**
     * 创建一个新的对话会话。
     * <p>使用说明：由前端"新建会话"操作触发；创建后返回会话 ID，后续消息发送需携带此 ID。</p>
     *
     * @param request 创建会话请求体，{@code agentId} 必填，非空
     * @return {@link ResponseEntity} 包含新建会话的元数据响应，HTTP 状态码 200
     */
    @PostMapping("/conversation/create")
    public ResponseEntity<ConversationResponse> createConversation(@RequestBody CreateConversationRequest request) {
        return ResponseEntity.ok(chatBiz.createConversation(request));
    }

    /**
     * 列出当前登录用户下的所有活跃会话。
     * <p>使用说明：由前端会话侧边栏初始化时调用，userId 从 UserContext 自动读取，无需传参。</p>
     *
     * @return {@link ResponseEntity} 包含会话列表，HTTP 状态码 200；无数据时返回空列表
     */
    @GetMapping("/conversation/list")
    public ResponseEntity<List<ConversationResponse>> listConversations() {
        return ResponseEntity.ok(chatBiz.listConversations());
    }

    /**
     * 删除指定 ID 的会话及其全部历史消息记录。
     * <p>使用说明：由前端会话列表"删除"操作触发，?conversationId= 传入目标会话 ID；操作不可逆。</p>
     *
     * @param conversationId 待删除的会话唯一 ID，非空，通过查询参数 {@code ?conversationId=} 传入
     * @return {@link ResponseEntity} 无返回值，HTTP 状态码 204
     */
    @DeleteMapping("/conversation/delete")
    public ResponseEntity<Void> deleteConversation(@RequestParam("conversationId") String conversationId) {
        chatBiz.deleteConversation(conversationId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 同步发送消息给 Agent，等待完整推理后返回。
     * <p>使用说明：适合短消息或对延迟不敏感的场景；长推理或多工具调用建议改用 SSE 流式接口。</p>
     *
     * @param request 发送消息请求体，{@code conversationId}、{@code content} 必填
     * @return {@link ResponseEntity} 包含 Agent 回复内容的响应对象，HTTP 状态码 200
     */
    @PostMapping("/message")
    public ResponseEntity<SendMessageResponse> sendMessage(@RequestBody SendMessageRequest request) {
        return ResponseEntity.ok(chatBiz.sendMessage(request));
    }

    /**
     * 以 SSE 流式方式发送消息，实时推送 Agent 推理过程与最终回复给前端。
     * <p>使用说明：推荐用于生产环境。事件类型说明：
     * {@code reasoning} — 思考过程文本块；{@code tool_result} — 工具执行结果；
     * {@code message} — 最终回复内容块；无 event 的控制帧 {@code [CONV_ID]} 携带会话 ID，
     * {@code [DONE]} 表示流结束。</p>
     *
     * @param request 发送消息请求体，{@code conversationId} 可为空（将自动建会话），{@code content} 必填且非空
     * @return {@link Flux} SSE 事件流；流结束时发送 {@code [DONE]} 控制帧
     */
    @PostMapping(value = "/message/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> sendMessageStream(@RequestBody SendMessageRequest request) {
        return chatBiz.sendMessageStream(request)
                .map(new Function<ChatStreamEvent, ServerSentEvent<String>>() {
                    @Override
                    public ServerSentEvent<String> apply(ChatStreamEvent evt) {
                        ServerSentEvent.Builder<String> builder = ServerSentEvent.builder(evt.getData());
                        if (evt.getEvent() != null && !evt.getEvent().isBlank()) {
                            builder.event(evt.getEvent());
                        }
                        return builder.build();
                    }
                });
    }

    /**
     * 获取指定会话的全部历史消息记录。
     * <p>使用说明：由前端切换会话时调用，?conversationId= 传入目标会话 ID；
     * 消息按时间正序排列，包含用户消息与 Agent 回复。</p>
     *
     * @param conversationId 目标会话唯一 ID，非空，通过查询参数 {@code ?conversationId=} 传入
     * @return {@link ResponseEntity} 包含历史消息列表（正序），HTTP 状态码 200；无历史时返回空列表
     */
    @GetMapping("/history")
    public ResponseEntity<List<ChatMessageResponse>> getHistory(@RequestParam("conversationId") String conversationId) {
        return ResponseEntity.ok(chatBiz.getHistory(conversationId));
    }
}
