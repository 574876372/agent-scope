package com.cl.agent.config.agent;

import com.cl.agent.enums.MemoryMode;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 记忆管理全局配置属性
 * <p>对应 {@code application.yml} 中的 {@code agent.memory.*} 配置段。</p>
 * <p>Agent 维度的 {@code memoryMode} 和 {@code maxTurns} 优先级高于此处的全局默认值。</p>
 *
 * <pre>
 * agent:
 *   memory:
 *     default-mode: SUMMARY   # 全局默认记忆模式
 *     max-turns: 10           # 全局默认窗口轮数
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.memory")
public class AgentMemoryProperties {

    /**
     * 全局默认记忆模式。
     * 当 Agent 的 {@code memoryMode} 字段为空时，使用此值。
     * 默认值：{@link MemoryMode#SUMMARY}
     */
    private MemoryMode defaultMode = MemoryMode.SUMMARY;

    /**
     * 全局默认记忆窗口上限（轮数，一轮 = 1条 user + 1条 assistant）。
     * 当 Agent 的 {@code maxTurns} 字段为 null 时，使用此值。
     * FULL 模式下忽略此字段。
     * 默认值：10
     */
    private int maxTurns = 10;
}
