package com.cl.agent.controller;

import com.cl.agent.dto.*;
import com.cl.agent.service.IChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*")
public class ChatController {

    @Autowired
    private IChatService chatService;

    /** 创建会话 */
    @PostMapping("/conversation")
    public ResponseEntity<ConversationResponse> createConversation(@RequestBody CreateConversationRequest request) {
        return ResponseEntity.ok(chatService.createConversation(request));
    }

    /** 获取所有会话 */
    @GetMapping("/conversations")
    public ResponseEntity<List<ConversationResponse>> listConversations() {
        return ResponseEntity.ok(chatService.listConversations());
    }

    /** 删除会话 */
    @DeleteMapping("/conversation/{conversationId}")
    public ResponseEntity<Void> deleteConversation(@PathVariable String conversationId) {
        chatService.deleteConversation(conversationId);
        return ResponseEntity.noContent().build();
    }

    /** 发送消息 */
    @PostMapping("/message")
    public ResponseEntity<SendMessageResponse> sendMessage(@RequestBody SendMessageRequest request) {
        return ResponseEntity.ok(chatService.sendMessage(request));
    }

    /** 获取会话历史 */
    @GetMapping("/history/{conversationId}")
    public ResponseEntity<List<ChatMessageResponse>> getHistory(@PathVariable String conversationId) {
        return ResponseEntity.ok(chatService.getHistory(conversationId));
    }
}
