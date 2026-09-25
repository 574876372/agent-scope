package com.cl.agent.biz;

import com.cl.agent.dto.ChatStreamEvent;
import com.cl.agent.dto.SendMessageRequest;
import reactor.core.publisher.Flux;

/**
 * 通用人机协同（HITL）审批执行的业务层编排接口。
 * <p>使用说明：当用户在前端对标记为敏感的工具点击确认/编辑/取消时，
 * 控制层会将请求转发到本业务接口，负责核销 Token、反射调用真实工具、并将执行结果交由大模型进行二次总结，生成 SSE 事件流推送给前端。</p>
 */
public interface IGenericHitlBiz {

    /**
     * 处理人类对敏感工具审批的确认、修改或拒绝请求。
     * <p>使用说明：由 {@code ChatBizImpl#sendMessageStream} 拦截到 {@code hitlAction} 非空时短路触发；
     * 内部会消费 Token，如果是 APPROVE/EDIT 则执行真实反射逻辑并调用 LLM 流式产出二次总结，若是 REJECT 则推送取消消息并完成流。</p>
     *
     * @param request 发送消息请求体，包含 {@code hitlAction}、{@code hitlToken} 以及可选的 {@code editedParameters}，非空
     * @return 响应式的 {@link ChatStreamEvent} 事件流；包含中间过程事件（如 tool_result、reasoning 等）以及最终总结，结束时发送 {@code [DONE]} 帧
     */
    Flux<ChatStreamEvent> confirmExecution(SendMessageRequest request);
}
