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

@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*")
public class ChatController {

    @Autowired
    private IChatBiz chatBiz;

    /** 创建会话 */
    @PostMapping("/conversation")
    public ResponseEntity<ConversationResponse> createConversation(@RequestBody CreateConversationRequest request) {
        return ResponseEntity.ok(chatBiz.createConversation(request));
    }

    /** 获取所有会话 */
    @GetMapping("/conversations")
    public ResponseEntity<List<ConversationResponse>> listConversations() {
        return ResponseEntity.ok(chatBiz.listConversations());
    }

    /** 删除会话 */
    @DeleteMapping("/conversation/{conversationId}")
    public ResponseEntity<Void> deleteConversation(@PathVariable String conversationId) {
        chatBiz.deleteConversation(conversationId);
        return ResponseEntity.noContent().build();
    }

    /** 发送消息 */
    @PostMapping("/message")
    public ResponseEntity<SendMessageResponse> sendMessage(@RequestBody SendMessageRequest request) {
        return ResponseEntity.ok(chatBiz.sendMessage(request));
    }

    /**
     * 流式发送消息（SSE）。
     * <p>
     * 事件类型：{@code reasoning} 思考过程、{@code tool_result} 工具结果、{@code message} 最终回复；
     * 无 event 的控制帧：{@code [CONV_ID]}、{@code [DONE]}。
     * </p>
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

    /** 获取会话历史 */
    @GetMapping("/history/{conversationId}")
    public ResponseEntity<List<ChatMessageResponse>> getHistory(@PathVariable String conversationId) {
        return ResponseEntity.ok(chatBiz.getHistory(conversationId));
    }
}
