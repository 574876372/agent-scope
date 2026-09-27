package com.cl.agent.biz.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.List;

/**
 * Agent 运行时缓存失效事件。
 * <p>使用说明：知识库删除、知识库检索配置变更等会影响已缓存 Agent 行为的操作完成后发布；
 * {@code AgentBizImpl} 监听后移除对应 Agent 的运行时实例，下次对话按最新数据重建。
 * 同步事件，发布方返回时缓存已失效。</p>
 */
@Getter
public class AgentCacheEvictEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    /** 需要失效的 Agent ID 列表；为空列表时不做任何处理 */
    private final List<String> agentIds;

    /** 触发原因，仅用于日志排查，如 "知识库删除: kbId=xxx" */
    private final String reason;

    /**
     * 构造缓存失效事件。
     *
     * @param source   事件发布方，非空
     * @param agentIds 需要失效的 Agent ID 列表，可为空列表
     * @param reason   触发原因描述，用于日志
     */
    public AgentCacheEvictEvent(Object source, List<String> agentIds, String reason) {
        super(source);
        this.agentIds = agentIds != null ? agentIds : List.of();
        this.reason = reason;
    }
}
