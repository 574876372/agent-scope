package com.cl.agent.enums;

/**
 * Agent 记忆模式枚举
 * <p>控制每次对话时历史消息的处理策略。</p>
 */
public enum MemoryMode {

    /**
     * 全量记忆：加载所有历史消息，不做任何压缩，直接发送给模型。
     * <p><b>注意</b>：历史过长时存在 Token 超限风险，适合短对话或对上下文完整性要求极高的场景。</p>
     */
    FULL,

    /**
     * 纯滑动窗口：仅保留最近 N 轮对话，超出部分直接丢弃。
     * <p>成本最低，但会丢失超出窗口的历史语境。</p>
     */
    WINDOW,

    /**
     * 摘要 + 滑动（默认）：超过窗口阈值后，先调用 LLM 对旧消息生成摘要并持久化，
     * 再将摘要作为 system 消息拼接到最近 N 轮上下文头部。
     * <p>在控制 Token 用量的同时保留历史语境，适合通用场景。</p>
     */
    SUMMARY;

    /**
     * 安全解析枚举值，解析失败时返回默认值 {@link #SUMMARY}。
     *
     * @param value 字符串值
     * @return 对应的 {@link MemoryMode}，解析失败时返回 SUMMARY
     */
    public static MemoryMode of(String value) {
        if (value == null || value.isBlank()) {
            return SUMMARY;
        }
        try {
            return MemoryMode.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return SUMMARY;
        }
    }
}
