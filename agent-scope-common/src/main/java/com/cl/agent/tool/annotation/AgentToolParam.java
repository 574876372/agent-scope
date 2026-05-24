package com.cl.agent.tool.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记 {@link AgentTool} 方法的 LLM 可见入参。
 * <p>由于 Java 编译默认不保留参数名，必须通过 {@link #name()} 显式指定参数名。</p>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AgentToolParam {

    /**
     * 参数名称，需与 {@link AgentTool#parametersSchema()} 中的 property key 一致。
     *
     * @return 参数名
     */
    String name();

    /**
     * 参数描述，可选；主要 Schema 信息以 {@link AgentTool#parametersSchema()} 为准。
     *
     * @return 参数说明
     */
    String description() default "";
}
