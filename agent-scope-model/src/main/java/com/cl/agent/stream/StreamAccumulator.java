package com.cl.agent.stream;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 流式回复累积器，收集 Agent 推送的各类事件片段，最终拼装为可持久化的完整文本。
 * <p>持久化格式：{@code <think>…</think>}{工具摘要行}{最终回复}</p>
 */
public class StreamAccumulator {

    /**
     * 用于从工具结果 JSON 中提取 tool 名称和 output 内容的正则表达式。
     * 匹配格式：{"tool":"...","output":"..."}，output 支持转义字符。
     */
    private static final Pattern TOOL_JSON_PATTERN =
            Pattern.compile("\"tool\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"output\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"}");

    /** 累积 Agent 的推理过程文本（EventType.REASONING），持久化时包裹在 {@code <think>…</think>} 标签内 */
    private final StringBuilder reasoning = new StringBuilder();

    /** 累积工具调用结果，每条格式为 {@code "Action: {tool}\nObservation: {output}\n"}，供持久化使用 */
    private final StringBuilder tools = new StringBuilder();

    /** 累积最终回复文本（EventType.AGENT_RESULT 及其他），即展示给用户的答案 */
    private final StringBuilder message = new StringBuilder();

    /**
     * 追加推理片段。
     *
     * @param chunk 推理文本片段，为 null 或空时忽略
     */
    public void appendReasoning(String chunk) {
        if (chunk != null && !chunk.isEmpty()) {
            reasoning.append(chunk);
        }
    }

    /**
     * 追加最终回复片段。
     *
     * @param chunk 回复文本片段，为 null 或空时忽略
     */
    public void appendMessage(String chunk) {
        if (chunk != null && !chunk.isEmpty()) {
            message.append(chunk);
        }
    }

    /**
     * 解析工具结果 JSON 并追加到工具摘要中。
     *
     * @param json 格式为 {@code {"tool":"...","output":"..."}} 的 JSON 字符串
     */
    public void appendToolResultJson(String json) {
        Matcher m = TOOL_JSON_PATTERN.matcher(json);
        if (m.find()) {
            String tool = unescapeJsonString(m.group(1));
            String output = unescapeJsonString(m.group(2));
            tools.append("Action: ").append(tool).append("\nObservation: ").append(output).append("\n");
        }
    }

    /**
     * 拼装完整持久化内容：{@code <think>推理</think>}{工具摘要}{最终回复}。
     *
     * @return 可直接存入数据库的完整文本
     */
    public String buildPersistContent() {
        StringBuilder sb = new StringBuilder();
        if (reasoning.length() > 0) {
            sb.append("<think>").append(reasoning).append("</think>");
        }
        sb.append(tools);
        sb.append(message);
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // 内部工具方法
    // -------------------------------------------------------------------------

    /**
     * 将 JSON 转义字符串还原为普通字符串。
     *
     * @param escaped 含转义序列的字符串
     * @return 还原后的字符串
     */
    private static String unescapeJsonString(String escaped) {
        if (escaped == null) {
            return "";
        }
        return escaped
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }
}
