package com.cl.agent.biz.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 文档异步解析与向量化任务触发事件。
 * <p>使用说明：由 {@code KnowledgeBizImpl#uploadAndIndexDocument} 在文档元数据落库后发布；
 * {@code DocumentIndexEventListener} 订阅此事件，在专属异步线程池中完成文件解析、
 * 切片向量化与 MySQL 状态更新等耗时操作，彻底解耦主请求链路。</p>
 * <p>携带 {@code userId} 字段以解决 {@code UserContext}（ThreadLocal）跨线程传递问题，
 * 监听器线程在处理前需手动恢复用户上下文。</p>
 */
@Getter
public class DocumentIndexEvent extends ApplicationEvent {

    /** 待解析向量化的文档唯一 ID，对应 {@code t_knowledge_document.id} */
    private final String fileId;

    /**
     * 发布事件时所在请求线程的用户 ID。
     * <p>因 {@code UserContext} 基于 ThreadLocal 实现，跨线程传递时必须显式携带此值，
     * 在监听器中调用 {@code UserContext.setUserId(userId)} 恢复上下文，
     * 处理完成后调用 {@code UserContext.clear()} 防止线程池污染。</p>
     */
    private final String userId;

    /**
     * 构造文档向量化事件。
     *
     * @param source 事件来源，通常传入发布方的 {@code this} 引用
     * @param fileId 文档唯一 ID，非空
     * @param userId 当前请求用户 ID，可为 null（线程局部存储不存在时）
     */
    public DocumentIndexEvent(Object source, String fileId, String userId) {
        super(source);
        this.fileId = fileId;
        this.userId = userId;
    }
}
