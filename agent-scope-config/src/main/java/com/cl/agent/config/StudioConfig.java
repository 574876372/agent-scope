package com.cl.agent.config;

import io.agentscope.core.studio.StudioManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * AgentScope Studio 可视化面板集成配置。
 * <p>
 * 当配置项 {@code agentscope.studio.enabled=true} 时，自动初始化并连接 Studio Server。
 * Studio Server 需提前启动（运行 {@code as_studio}），默认监听 {@code http://localhost:5173}。
 * </p>
 *
 * <p>application.yml 示例：</p>
 * <pre>
 *   agentscope:
 *     studio:
 *       enabled: true
 *       url: http://localhost:5173
 *       project: my-project
 * </pre>
 *
 * @see io.agentscope.core.studio.StudioManager
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "agentscope.studio", name = "enabled", havingValue = "true")
public class StudioConfig {

    /** Studio Server 地址，默认 http://localhost:5173 */
    @Value("${agentscope.studio.url:http://localhost:5173}")
    private String studioUrl;

    /** Studio 项目名称，用于在面板中区分不同项目 */
    @Value("${agentscope.studio.project:agent-scope-project}")
    private String project;

    /**
     * 应用启动后自动连接 Studio Server。
     * <p>{@link StudioManager} 为纯静态工具类，连接成功后可通过
     * {@link StudioManager#isInitialized()} 判断状态，
     * 通过 {@link StudioManager#getClient()} 获取客户端实例。</p>
     */
    @PostConstruct
    public void initStudio() {
        log.info("[Studio] 正在连接 AgentScope Studio: url={}, project={}", studioUrl, project);
        StudioManager.init()
                .studioUrl(studioUrl)
                .project(project)
                .runName("run_" + System.currentTimeMillis())
                .initialize()
                .block();
        log.info("[Studio] 连接成功 ✓ 访问 {} 查看可视化推理面板", studioUrl);
    }

    /**
     * 应用关闭时优雅释放 Studio 连接资源。
     */
    @PreDestroy
    public void onShutdown() {
        log.info("[Studio] 应用关闭，释放 Studio 连接...");
        StudioManager.shutdown();
    }
}
