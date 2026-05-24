package com.cl.agent.tool.core;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
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
import java.util.Map;

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

    /**
     * 异步执行注解工具方法，异常包装为 {@link ToolResultBlock#error(String)}。
     *
     * @param param AgentScope 传入的调用参数
     * @return 工具执行结果
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> invokeMethod(param.getInput()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(this::toResultBlock)
                .onErrorResume(error -> {
                    log.warn("[Tool] 工具 {} 执行失败: {}", metadata.name(), error.getMessage());
                    return Mono.just(ToolResultBlock.error("Tool execution failed: " + error.getMessage()));
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
