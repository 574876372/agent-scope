package com.cl.agent.biz.event;

import org.springframework.context.ApplicationEvent;

/**
 * 模型配置变更事件。
 * <p>厂商或模型被修改、删除后发布。缓存了模型客户端的组件（如智能体运行时实例缓存）订阅本事件后清理缓存，
 * 下次使用时按最新配置重建。仅在本节点内传播；多实例部署时其它节点的智能体缓存需重启或等待其自身失效。</p>
 */
public class ModelConfigChangedEvent extends ApplicationEvent {

    public ModelConfigChangedEvent(Object source) {
        super(source);
    }
}
