package com.cl.agent.hitl.core;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * 审批上下文实体类。
 * <p>使用说明：记录被拦截的敏感工具调用的关键信息，包括生成的 Token、所属用户、会话、工具名称、原始参数和参数 Schema，
 * 存放在 TokenStore 中，等待用户审批通过后提取还原执行。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalContext {

    /** 审批 Token 唯一标识符 */
    private String token;

    /** 提交此调用请求的用户 ID */
    private String userId;

    /** 关联的会话 ID，可空 */
    private String conversationId;

    /** 被调用的工具名称，例如 "send_email" */
    private String toolName;

    /** 大模型传入的原始参数键值对 Map */
    private Map<String, Object> parameters;

    /** 该工具参数定义的 JSON Schema 结构，用于前端动态渲染交互表单 */
    private Map<String, Object> parameterSchema;

    /** 预检生成的元数据 Map（如估算行数、警告信息） */
    private Map<String, Object> preCheckMeta;

    /** 审批请求创建时间戳 */
    private Instant createdAt;

    /** 审批 Token 过期时间戳 */
    private Instant expiresAt;
}
