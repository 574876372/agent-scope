package com.cl.agent.controller;

import com.cl.agent.dto.*;
import com.cl.agent.biz.IChatBiz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    /** 获取会话历史 */
    @GetMapping("/history/{conversationId}")
    public ResponseEntity<List<ChatMessageResponse>> getHistory(@PathVariable String conversationId) {
        return ResponseEntity.ok(chatBiz.getHistory(conversationId));
    }
}
