package com.cl.agent.dto;

import com.cl.agent.enums.SqlAction;
import lombok.Data;

import java.io.Serializable;

/**
 * 发送消息请求参数。
 *
 * <p>同一接口 {@code /api/chat/message/stream} 同时承担两类请求：
 * <ol>
 *   <li><b>普通聊天</b>：{@link #content} 必填，{@link #sqlAction} 为 null —— 进入常规 LLM 推理流程。</li>
 *   <li><b>HITL SQL 确认</b>：{@link #sqlAction} 非 null，{@link #confirmToken} 必填；
 *       {@code ChatBizImpl} 入口短路到 {@code SqlAgentBizImpl.confirmSqlExecution}，不进 LLM。</li>
 * </ol>
 *
 * 复用同一 DTO 而不新增 {@code SqlConfirmRequest} 的目的：减少前后端 API 维护面、避免 endpoint 膨胀。
 */
@Data
public class SendMessageRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 会话 ID，为空时自动创建新会话（仅普通聊天场景生效；HITL 确认必须传入原会话 ID） */
    private String conversationId;

    /** 用户发送的消息内容；HITL 确认时可为空字符串 */
    private String content;

    /**
     * HITL SQL 确认动作。
     * <ul>
     *   <li>{@code null} —— 普通聊天</li>
     *   <li>{@code APPROVE} —— 执行 {@link #confirmToken} 对应原 SQL</li>
     *   <li>{@code REJECT}  —— 取消执行，仅记审计</li>
     *   <li>{@code EDIT}    —— 用 {@link #editedSql} 重过守卫后执行</li>
     * </ul>
     */
    private SqlAction sqlAction;

    /** 与上次 PENDING_APPROVAL 工具结果中下发的 token 对应；sqlAction 非空时必填 */
    private String confirmToken;

    /** 用户在审批卡片中编辑后的 SQL，仅 {@code sqlAction=EDIT} 时使用 */
    private String editedSql;
}
