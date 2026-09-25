package com.cl.agent.tool.core;

import java.util.Map;
import lombok.Getter;
import lombok.Builder;
import lombok.AllArgsConstructor;

/**
 * 工具调用拦截器上下文对象。
 * <p>使用说明：该类封装了拦截器执行所需的反射工具包装实例和大模型传入的工具入参 Map。
 * 在 {@link ToolInterceptor#intercept} 方法中作为唯一入参，以提供更强的扩展性与清晰的参数定义。
 * 所属分层：工具插件 Starter 核心层。</p>
 */
@Getter
@Builder
@AllArgsConstructor
public class ToolInterceptorContext {

    /**
     * 正在执行的反射工具包装实例，非空。
     */
    private final ReflectiveAgentTool tool;

    /**
     * 大模型传入的入参 Map，非空。
     */
    private final Map<String, Object> input;
}
