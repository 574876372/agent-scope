package com.cl.agent.tool.core;

import com.cl.agent.tool.annotation.AgentToolDef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 扫描 Spring 容器中所有 {@link AgentToolDef} 注解方法，维护工具注册表。
 */
@Slf4j
public class AgentToolRegistry implements SmartInitializingSingleton {

    /** Spring Bean 工厂，用于扫描所有 Bean */
    private final ListableBeanFactory beanFactory;

    /** 工具名称 -> 反射包装器 */
    private final Map<String, ReflectiveAgentTool> registeredTools = new LinkedHashMap<>();

    /** 工具注册完成后的可选同步回调（用于将元数据同步到数据库等外部存储） */
    private final Optional<ToolRegistrySyncCallback> syncCallback;

    /**
     * 构造注册表。
     *
     * @param beanFactory  Spring Bean 工厂
     * @param syncCallback 可选的同步回调
     */
    public AgentToolRegistry(ListableBeanFactory beanFactory,
                             Optional<ToolRegistrySyncCallback> syncCallback) {
        this.beanFactory = beanFactory;
        this.syncCallback = syncCallback;
    }

    /**
     * 所有单例 Bean 初始化完成后扫描并注册 {@link AgentToolDef} 方法。
     */
    @Override
    public void afterSingletonsInstantiated() {
        scanAndRegister();
    }

    /**
     * 扫描 Spring 容器中所有 {@link AgentToolDef} 注解方法并写入注册表。
     * <p>使用说明：在 Spring 单例 Bean 初始化完成后，由 {@link #afterSingletonsInstantiated()} 自动触发调用。</p>
     */
    public void scanAndRegister() {
        try {
            List<ToolInterceptor> interceptors = new ArrayList<>();
            Map<String, ToolInterceptor> beans = beanFactory.getBeansOfType(ToolInterceptor.class);
            if (beans != null) {
                interceptors.addAll(beans.values());
            }
            log.info("[Tool] 扫描到 {} 个全局工具拦截器", interceptors.size());

            String[] beanNames = beanFactory.getBeanDefinitionNames();
            for (String beanName : beanNames) {
                try {
                    Object bean = beanFactory.getBean(beanName);
                    registerBeanMethods(bean, interceptors);
                } catch (Exception ex) {
                    log.debug("[Tool] 获取 Bean {} 失败，跳过", beanName, ex);
                }
            }
            log.info("[Tool] 已注册 {} 个 Agent 工具: {}", registeredTools.size(), registeredTools.keySet());

            // 通知同步回调（如将工具元数据持久化到数据库）
            if (syncCallback.isPresent()) {
                syncCallback.get().onToolsRegistered(getAllTools());
            }
        } catch (Exception e) {
            log.warn("[Tool] 扫描并注册工具遇到异常", e);
        }
    }

    /**
     * 扫描单个 Bean 中的 {@link AgentToolDef} 方法并注入拦截器链后注册。
     * <p>使用说明：由 {@link #scanAndRegister()} 内部循环调用。</p>
     *
     * @param bean         Spring Bean 实例，非空
     * @param interceptors 扫描到的全局拦截器列表，非空
     */
    private void registerBeanMethods(Object bean, List<ToolInterceptor> interceptors) {
        Class<?> clazz = bean.getClass();
        for (Method method : clazz.getDeclaredMethods()) {
            AgentToolDef annotation = method.getAnnotation(AgentToolDef.class);
            if (annotation == null) {
                continue;
            }
            ReflectiveAgentTool tool = new ReflectiveAgentTool(bean, method, annotation);
            tool.setInterceptors(interceptors);
            ReflectiveAgentTool previous = registeredTools.putIfAbsent(annotation.name(), tool);
            if (previous != null) {
                throw new IllegalStateException("重复的工具名称: " + annotation.name());
            }
            log.debug("[Tool] 注册工具: name={}, bean={}, method={}",
                    annotation.name(), clazz.getSimpleName(), method.getName());
        }
    }

    /**
     * 获取全部已注册工具。
     *
     * @return 不可变工具集合
     */
    public Collection<ReflectiveAgentTool> getAllTools() {
        return Collections.unmodifiableCollection(registeredTools.values());
    }

    /**
     * 获取所有已注册的工具名称集合。
     *
     * @return 不可变工具名称集合
     */
    public Set<String> getRegisteredToolNames() {
        return Collections.unmodifiableSet(registeredTools.keySet());
    }

    /**
     * 按名称获取工具。
     *
     * @param name 工具名称
     * @return 工具实例，不存在时返回 null
     */
    public ReflectiveAgentTool getTool(String name) {
        return registeredTools.get(name);
    }

    /**
     * 按名称列表解析工具，忽略不存在的名称并记录警告。
     *
     * @param toolNames 工具名称列表，null 或空时返回全部已注册工具
     * @return 匹配的工具列表
     */
    public List<ReflectiveAgentTool> resolveTools(List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return registeredTools.values().stream().collect(Collectors.toList());
        }
        return toolNames.stream()
                .map(name -> {
                    ReflectiveAgentTool tool = registeredTools.get(name);
                    if (tool == null) {
                        log.warn("[Tool] 未找到工具: {}", name);
                    }
                    return tool;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
}
