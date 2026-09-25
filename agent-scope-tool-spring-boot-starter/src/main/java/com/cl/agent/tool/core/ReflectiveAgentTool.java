package com.cl.agent.tool.core;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.cl.agent.commons.UserContext;
import com.cl.agent.tool.annotation.AgentToolDef;
import io.agentscope.core.tool.AgentTool;
import com.cl.agent.tool.annotation.AgentToolParam;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 将 {@link AgentToolDef} 注解方法包装为 AgentScope {@link AgentTool}。
 */
@Slf4j
public class ReflectiveAgentTool implements AgentTool {

    /** 被注解标记的工具方法所属 Bean 实例 */
    private final Object bean;

    /** 被 {@link AgentToolDef} 标记的可调用方法 */
    private final Method method;

    /** 工具元数据注解 */
    private final AgentToolDef metadata;

    /** 解析后的 JSON Schema 参数定义 */
    private final Map<String, Object> parameters;

    /**
     * 构造反射工具包装器。
     *
     * @param bean     Spring Bean 实例
     * @param method   带 {@link AgentToolDef} 的方法
     * @param metadata 工具注解元数据
     */
    public ReflectiveAgentTool(Object bean, Method method, AgentToolDef metadata) {
        this.bean = bean;
        this.method = method;
        this.metadata = metadata;
        this.parameters = parseParametersSchema(metadata.parametersSchema());
        this.method.setAccessible(true);
    }

    /**
     * 获取工具方法所属 Bean 实例。
     *
     * @return Spring Bean
     */
    public Object getBean() {
        return bean;
    }

    /**
     * 获取工具注解元数据。
     *
     * @return AgentToolDef 注解
     */
    public AgentToolDef getMetadata() {
        return metadata;
    }

    /**
     * 获取被注解标记的方法。
     *
     * @return 工具方法
     */
    public Method getToolMethod() {
        return method;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getName() {
        return metadata.name();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getDescription() {
        return metadata.description();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, Object> getParameters() {
        return parameters;
    }

    /** 拦截器列表，由 Registry 统一查找并在扫描完成后注入，不可为空 */
    private List<ToolInterceptor> interceptors = Collections.emptyList();

    /**
     * 设置工具调用拦截器列表。
     * <p>使用说明：由 {@link AgentToolRegistry} 在扫描注册工具时统一注入拦截器。</p>
     *
     * @param interceptors 拦截器列表，非空
     */
    public void setInterceptors(List<ToolInterceptor> interceptors) {
        this.interceptors = Objects.requireNonNull(interceptors, "interceptors");
    }

    /**
     * 异步执行注解工具方法。执行前会依次通过 {@link ToolInterceptor} 链式拦截；
     * 若被拦截则短路返回拦截结果，否则反射调用真实 Bean 方法。异常会被包装为 {@link ToolResultBlock#error(String)}。
     *
     * @param param AgentScope 传入的调用参数
     * @return 工具执行结果
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput() != null ? param.getInput() : Collections.emptyMap();

        // 1. 链式执行拦截器，首个返回非空结果的拦截器会短路执行
        Mono<ToolResultBlock> interceptChain = Mono.empty();
        if (interceptors != null) {
            ToolInterceptorContext context = ToolInterceptorContext.builder()
                    .tool(this)
                    .input(input)
                    .build();
            for (ToolInterceptor interceptor : interceptors) {
                interceptChain = interceptChain.switchIfEmpty(
                        Mono.defer(() -> interceptor.intercept(context))
                );
            }
        }

        // 2. 如果没有任何拦截器拦截（全部返回 empty），则执行真实的反射方法
        return interceptChain.switchIfEmpty(
                Mono.deferContextual(ctx -> {
                    String userId = ctx.getOrDefault("userId", null);
                    return Mono.fromCallable(() -> {
                        String oldUserId = UserContext.getUserId();
                        try {
                            if (userId != null) {
                                UserContext.setUserId(userId);
                            }
                            return invokeMethod(input);
                        } finally {
                            if (oldUserId != null) {
                                UserContext.setUserId(oldUserId);
                            } else {
                                UserContext.clear();
                            }
                        }
                    })
                    .map(this::toResultBlock);
                }))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(error -> {
                    log.warn("[Tool] 工具 {} 执行失败: {}", metadata.name(), error.getMessage(), error);
                    // 构建结构化的错误 Observation，帮助 LLM 理解问题并尝试修正
                    String observation = String.format(
                            "工具 [%s] 执行失败。错误类型: %s，错误信息: %s。请检查输入参数后重试或换用其他方式回答。",
                            metadata.name(),
                            error.getClass().getSimpleName(),
                            error.getMessage() != null ? error.getMessage() : "未知错误"
                    );
                    return Mono.just(ToolResultBlock.error(observation));
                });
    }

    /**
     * 绕过拦截器链，直接反射执行真实的工具方法。
     * <p>使用说明：在人机协同（HITL）审批通过或编辑确认后，由业务执行器直接调用该方法执行工具逻辑。</p>
     *
     * @param input  工具入参 Map，非空
     * @return 包含 {@link ToolResultBlock} 包装的工具执行结果 Mono
     */
    public Mono<ToolResultBlock> executeDirectly(Map<String, Object> input) {
        return Mono.deferContextual(ctx -> {
            String userId = ctx.getOrDefault("userId", null);
            return Mono.fromCallable(() -> {
                String oldUserId = UserContext.getUserId();
                try {
                    if (userId != null) {
                        UserContext.setUserId(userId);
                    }
                    return invokeMethod(input);
                } finally {
                    if (oldUserId != null) {
                        UserContext.setUserId(oldUserId);
                    } else {
                        UserContext.clear();
                    }
                }
            })
            .map(this::toResultBlock);
        })
        .subscribeOn(Schedulers.boundedElastic())
        .onErrorResume(error -> {
            log.warn("[Tool] 工具 {} 直接执行失败: {}", metadata.name(), error.getMessage(), error);
            String observation = String.format(
                    "工具 [%s] 执行失败。错误类型: %s，错误信息: %s。请检查输入参数后重试或换用其他方式回答。",
                    metadata.name(),
                    error.getClass().getSimpleName(),
                    error.getMessage() != null ? error.getMessage() : "未知错误"
            );
            return Mono.just(ToolResultBlock.error(observation));
        });
    }

    /**
     * 解析 JSON Schema 字符串为 Map。
     *
     * @param schema JSON Schema 文本
     * @return 参数 Schema Map
     */
    private Map<String, Object> parseParametersSchema(String schema) {
        if (schema == null || schema.isBlank()) {
            return Map.of("type", "object", "properties", Collections.emptyMap());
        }
        return JSON.parseObject(schema, new TypeReference<Map<String, Object>>() {
        });
    }

    /**
     * 将 LLM 传入的 input Map 转换为方法实参并反射调用。
     *
     * @param input LLM 工具调用参数
     * @return 方法返回值
     * @throws ReflectiveOperationException 反射调用异常
     */
    private Object invokeMethod(Map<String, Object> input) throws ReflectiveOperationException {
        Object[] args = buildArguments(input != null ? input : Collections.emptyMap());
        return method.invoke(bean, args);
    }

    /**
     * 根据 {@link AgentToolParam} 从 input 中提取并转换各参数值。
     *
     * @param input LLM 传入的参数 Map
     * @return 与方法签名对齐的参数数组
     */
    private Object[] buildArguments(Map<String, Object> input) {
        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            AgentToolParam toolParam = parameter.getAnnotation(AgentToolParam.class);
            if (toolParam == null) {
                throw new IllegalArgumentException(
                        "AgentTool 方法 " + method.getName() + " 的参数必须标注 @AgentToolParam");
            }
            Object rawValue = input.get(toolParam.name());
            args[i] = convertValue(rawValue, parameter.getType());
        }
        return args;
    }

    /**
     * 将 LLM 传入的原始值转换为目标 Java 类型。
     *
     * @param rawValue   原始值
     * @param targetType 目标类型
     * @return 转换后的值
     */
    private Object convertValue(Object rawValue, Class<?> targetType) {
        if (rawValue == null) {
            return null;
        }
        if (targetType.isInstance(rawValue)) {
            return rawValue;
        }
        if (targetType == String.class) {
            return String.valueOf(rawValue);
        }
        if (targetType == int.class || targetType == Integer.class) {
            return Integer.valueOf(String.valueOf(rawValue));
        }
        if (targetType == long.class || targetType == Long.class) {
            return Long.valueOf(String.valueOf(rawValue));
        }
        if (targetType == double.class || targetType == Double.class) {
            return Double.valueOf(String.valueOf(rawValue));
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            return Boolean.valueOf(String.valueOf(rawValue));
        }
        return JSON.parseObject(JSON.toJSONString(rawValue), targetType);
    }

    /**
     * 将方法返回值转为 {@link ToolResultBlock}。
     *
     * @param result 方法返回值
     * @return 工具结果块
     */
    private ToolResultBlock toResultBlock(Object result) {
        if (result == null) {
            return ToolResultBlock.text("");
        }
        if (result instanceof ToolResultBlock block) {
            return block;
        }
        if (result instanceof Mono<?>) {
            throw new IllegalStateException("Reactive return type should be handled at toolkit level");
        }
        return ToolResultBlock.text(String.valueOf(result));
    }
}
