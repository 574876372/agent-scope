package com.cl.agent.tool.core;

import io.agentscope.core.message.ToolResultBlock;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 工具调用拦截器接口。
 * <p>使用说明：实现此接口并注册为 Spring Bean，可用于在工具方法反射执行前进行拦截或改写；
 * 多个拦截器会链式执行。若某个拦截器返回非空结果，则短路执行并返回该结果，不会进入后置拦截器及真实方法体。</p>
 */
public interface ToolInterceptor {

    /**
     * 拦截工具执行。
     * <p>使用说明：在 {@link ReflectiveAgentTool#callAsync} 反射调用真实 Bean 之前被触发。</p>
     *
     * @param context 拦截器上下文对象，非空，包含正在执行的反射工具包装实例与大模型传入的参数 Map
     * @return 返回 {@link Mono#empty()} 表示通过（不拦截，继续执行）；
     *         返回包含 {@link ToolResultBlock} 的 Mono 则表示拦截并短路返回该特定结果。
     */
    Mono<ToolResultBlock> intercept(ToolInterceptorContext context);
}
