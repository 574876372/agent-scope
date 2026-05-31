package com.cl.agent.biz;

import com.cl.agent.dto.ChatStreamEvent;
import com.cl.agent.dto.SendMessageRequest;
import reactor.core.publisher.Flux;

/**
 * SQL Agent HITL 业务接口。
 *
 * <p>独立于 {@link IChatBiz}，专门处理 SQL 审批确认后的执行 + 二次总结。
 * 由 {@code ChatBizImpl.sendMessageStream} 在检测到 {@code request.getSqlAction() != null}
 * 时短路调用，复用同一 SSE 接口 {@code /api/chat/message/stream}。</p>
 */
public interface ISqlAgentBiz {

    /**
     * 执行 HITL SQL 确认流程并以 SSE 流式推送结果。
     *
     * <p>使用说明：调用方（{@code ChatBizImpl}）已校验 {@code sqlAction} 非空；
     * 本方法负责：
     * <ol>
     *   <li>校验 {@code confirmToken} + {@code conversationId} + {@code UserContext.getUserId}</li>
     *   <li>调用 starter {@code SqlConfirmExecutor} 取 token、执行 SQL、发审计</li>
     *   <li>推送 {@code tool_result} 帧（含执行结果 JSON）</li>
     *   <li>EXECUTED 时触发 LLM 二次总结（{@code agentBiz.chatStream}），其它状态推 {@code message} 提示</li>
     *   <li>持久化助手消息 + 推 {@code [DONE]} 结束流</li>
     * </ol>
     * 全链路异常都会被吞掉并推一帧 {@code error}，不会让 Flux 终止于异常状态。
     *
     * @param request 复用的请求体，要求 {@code sqlAction} + {@code confirmToken} + {@code conversationId} 非空
     * @return {@link ChatStreamEvent} 流；事件名同普通聊天（reasoning / tool_result / message / error），
     *         首帧为 {@code [CONV_ID]} 控制帧、末帧为 {@code [DONE]} 控制帧
     */
    Flux<ChatStreamEvent> confirmSqlExecution(SendMessageRequest request);
}
