package com.cl.agent.tool.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记该工具方法在执行前需要经过人工审批拦截。
 * <p>使用说明：标注在带有 {@link AgentToolDef} 的工具方法上；
 * 当 Agent 尝试调用该工具时，系统会自动拦截执行、生成一个审批 Token，并返回 {@code PENDING_APPROVAL} 状态给前端；
 * 只有用户在前端点击同意/修改并确认后，底层的真实方法体才会被反射执行。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresApproval {

    /**
     * 审批 Token 在缓存中的超时失效时间（单位：秒）。
     * <p>使用说明：用于控制该工具对应审批请求的有效期，过期后 Token 将被驱逐失效。</p>
     *
     * @return 审批超时时间秒数，默认 600 秒（10 分钟）
     */
    int timeoutSeconds() default 600;
}
