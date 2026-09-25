package com.cl.agent.hitl.core;

import com.alibaba.fastjson2.JSONObject;
import com.cl.agent.tool.annotation.RequiresApproval;
import com.cl.agent.tool.annotation.PreCheckHandler;
import com.cl.agent.tool.core.ReflectiveAgentTool;
import com.cl.agent.tool.core.ToolInterceptor;
import com.cl.agent.tool.core.ToolInterceptorContext;
import io.agentscope.core.message.ToolResultBlock;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * 敏感工具人机协同拦截器。
 * <p>使用说明：实现 {@link ToolInterceptor} 接口并声明为 Spring Bean；
 * 它会检查工具方法是否标注有 {@link RequiresApproval}。若是，则生成临时 Token 并阻止真实反射执行，
 * 封装并返回带有 {@code PENDING_APPROVAL} 状态的结构化 JSON 提示给 Agent。</p>
 */
@Slf4j
public class ToolApprovalInterceptor implements ToolInterceptor {

    /** 审批 Token 存储管理器 */
    private final GenericApprovalTokenStore tokenStore;

    /**
     * 构造拦截器。
     *
     * @param tokenStore Token 存储，非空
     */
    public ToolApprovalInterceptor(GenericApprovalTokenStore tokenStore) {
        this.tokenStore = Objects.requireNonNull(tokenStore, "tokenStore");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<ToolResultBlock> intercept(ToolInterceptorContext context) {
        ReflectiveAgentTool tool = context.getTool();
        Map<String, Object> input = context.getInput();
        if (!tool.getToolMethod().isAnnotationPresent(RequiresApproval.class)) {
            // 没有 @RequiresApproval 注解，不进行拦截，走正常反射执行通道
            return Mono.empty();
        }

        String toolName = tool.getName();

        return Mono.deferContextual(ctx -> {
            String userId = ctx.getOrDefault("userId", null);
            if (userId == null || userId.isBlank()) {
                log.warn("[HITL-Interceptor] 拦截工具 {} 失败：当前上下文未检测到 userId", toolName);
                String errorMsg = String.format("工具 [%s] 属于敏感工具需经过审批，但缺少当前用户上下文，拦截失败。", toolName);
                return Mono.just(ToolResultBlock.error(errorMsg));
            }

            log.info("[HITL-Interceptor] 检测到敏感工具调用，触发安全拦截: toolName={}, userId={}", toolName, userId);

            // 1. 尝试触发预检逻辑
            Map<String, Object> preCheckMeta = Collections.emptyMap();
            Object bean = tool.getBean();
            if (bean instanceof PreCheckHandler) {
                try {
                    preCheckMeta = ((PreCheckHandler) bean).preCheck(input);
                    log.debug("[HITL-Interceptor] 工具 {} 预检完成，预检数据大小: {}", toolName, preCheckMeta.size());
                } catch (Exception e) {
                    log.warn("[HITL-Interceptor] 工具 {} 预检异常", toolName, e);
                }
            }

            // 2. 颁发审批 Token
            String token = tokenStore.issue(userId, null, toolName, input, tool.getParameters(), preCheckMeta);

            // 3. 构建返回给 LLM/Agent 的统一格式化报文
            JSONObject payload = new JSONObject();
            payload.put("status", "PENDING_APPROVAL");
            payload.put("approvalToken", token);
            payload.put("token", token);
            payload.put("toolName", toolName);
            payload.put("parameters", input);
            payload.put("parameterSchema", tool.getParameters());
            if (preCheckMeta != null && !preCheckMeta.isEmpty()) {
                payload.put("preCheckMeta", preCheckMeta);
            }
            payload.put("message", String.format("工具 [%s] 已安全拦截并发起人工审批。请用户在审批卡片上进行 同意/修改/取消 操作。", toolName));

            log.info("[HITL-Interceptor] 成功为工具 {} 颁发令牌 token={}", toolName, token);
            return Mono.just(ToolResultBlock.text(payload.toJSONString()));
        });
    }
}
