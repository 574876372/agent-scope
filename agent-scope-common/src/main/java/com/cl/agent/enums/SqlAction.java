package com.cl.agent.enums;

/**
 * SQL HITL 人工确认动作。
 *
 * <p>使用场景：当 LLM 通过 {@code query_database} 工具发起 SQL 请求并返回 PENDING_APPROVAL 时，
 * 前端会渲染审批卡片；用户点击 执行/编辑/取消 后，再次调用 {@code /api/chat/message/stream}，
 * 在 {@code SendMessageRequest.sqlAction} 中携带本枚举值。后端 {@code ChatBizImpl} 入口据此短路
 * 到 {@code SqlAgentBizImpl.confirmSqlExecution}，由 starter 的 {@code SqlConfirmExecutor} 执行。</p>
 *
 * <p>放在 {@code agent-scope-common} 而非 starter 内部的原因：
 * 同时被 {@code agent-scope-model} 的 {@code SendMessageRequest} 与
 * starter 的 {@code SqlConfirmExecutor} 引用，置于 common 可避免 model 依赖 starter。</p>
 */
public enum SqlAction {

    /** 用户同意执行 token 对应的原 SQL */
    APPROVE,

    /** 用户拒绝执行；后端写审计 + 推一条提示消息，不真正访问数据库 */
    REJECT,

    /** 用户在卡片内编辑了 SQL；后端用 editedSql 重过 SqlGuardEngine 校验后再执行 */
    EDIT
}
