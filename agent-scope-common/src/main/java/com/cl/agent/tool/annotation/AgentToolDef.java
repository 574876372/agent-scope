package com.cl.agent.tool.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记可被 Agent 调用的 Java 方法。
 * <p>配合 {@link AgentToolParam} 描述入参，{@link #parametersSchema()} 以 JSON Schema 格式
 * 提供给 LLM 理解如何调用该工具。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AgentToolDef {

    /**
     * 工具名称，建议使用 snake_case（如 {@code get_weather}）。
     *
     * @return 工具唯一名称
     */
    String name();

    /**
     * 工具功能描述，供 LLM 决策是否调用。
     *
     * @return 功能说明
     */
    String description();

    /**
     * 工具入参的 JSON Schema 定义，格式示例：
     * <pre>{@code
     * {
     *   "type": "object",
     *   "properties": {
     *     "city": {"type": "string", "description": "城市名称"}
     *   },
     *   "required": ["city"]
     * }
     * }</pre>
     *
     * @return JSON Schema 字符串
     */
    String parametersSchema();
}
