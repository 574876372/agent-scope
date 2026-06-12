package com.cl.agent.config.agent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Spring 异步任务全局配置。
 * <p>使用说明：开启 {@code @EnableAsync} 支持，并向 Spring 容器注册名为 {@code ragExecutor}
 * 的专属线程池 Bean，供 {@code DocumentIndexEventListener} 的 {@code @Async("ragExecutor")}
 * 注解引用，驱动文档解析与向量化的后台异步执行。</p>
 *
 * <p>线程池参数说明：</p>
 * <ul>
 *   <li>核心线程数 4：支持 4 路文档并行向量化，兼顾资源与吞吐量</li>
 *   <li>最大线程数 8：突发流量时可弹性扩容至 8 线程</li>
 *   <li>队列容量 100：超出时触发拒绝策略，防止无限积压</li>
 *   <li>KeepAlive 60s：空闲超时后回收非核心线程</li>
 *   <li>拒绝策略 CallerRunsPolicy：队列满时由调用者线程兜底执行，不丢任务</li>
 * </ul>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * RAG 文档解析与向量化专属异步线程池。
     * <p>使用说明：通过 Bean 名 {@code ragExecutor} 注入，在 {@code @Async("ragExecutor")}
     * 注解方法中被 Spring 自动使用。不要在业务代码中直接 {@code @Autowired} 此 Bean，
     * 统一通过 {@code @Async} 注解驱动调用。</p>
     *
     * @return 配置完毕的线程池 Executor 实例
     */
    @Bean("ragExecutor")
    public Executor ragExecutor() {
        return new ThreadPoolExecutor(
                4,
                8,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r);
                    t.setName("rag-async-" + t.getId());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
