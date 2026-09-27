package com.cl.agent.biz.rag;

import com.cl.agent.dto.model.ModelConnection;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 检索查询改写器：结合最近几轮对话，把追问改写为可独立检索的完整问题。
 * <p>使用说明：由检索流水线第 ① 步调用。例如上一轮问「代发接口的请求参数」，本轮问「那响应参数呢？」，
 * 改写为「代发接口的响应参数」后再检索。改写使用智能体自己的对话模型（演练场使用默认对话模型），
 * 每次提问多一次模型调用（约 0.5~2 秒）；调用失败或输出异常时返回 null，由调用方沿用原问题。</p>
 */
@Slf4j
@Component
public class QueryRewriter {

    /** 改写请求超时时间；超时即放弃改写，不阻塞对话 */
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /** 改写输出的最大 token 数；检索词通常很短 */
    private static final long MAX_TOKENS = 200;

    /** 每条历史消息参与改写的最大字符数，避免长回答占满提示词 */
    private static final int HISTORY_MESSAGE_MAX = 300;

    /** 改写结果长度上限相对原问题的倍数，超出视为模型答非所问 */
    private static final int MAX_LENGTH_FACTOR = 4;

    /** 改写结果长度上限的附加余量（字符） */
    private static final int MAX_LENGTH_SLACK = 120;

    /** 助手消息中的推理与检索来源块 */
    private static final Pattern NON_ANSWER_BLOCK = Pattern.compile("(?s)<think>.*?</think>|<retrieval>.*?</retrieval>");

    /**
     * 工具调用摘要行。须在去除推理块之后单独匹配：持久化格式中 {@code Action:} 紧跟在 {@code </think>} 之后，
     * 去除前它并不在行首。
     */
    private static final Pattern TOOL_LINE = Pattern.compile("(?m)^(?:Action|Observation):[^\\n]*\\n?");

    /** 模型输出中可能出现的前缀，如「改写后的问题：」 */
    private static final Pattern OUTPUT_PREFIX = Pattern.compile("^(改写后的?问题|检索问题|问题|Query)\\s*[:：]\\s*");

    /** 系统提示词：只输出改写后的问题 */
    private static final String SYSTEM_PROMPT = "你是知识库检索的查询改写助手。根据对话历史，把用户最新的问题改写成一个脱离上下文也能理解、"
            + "适合在知识库中检索的完整问题：补全省略的主语与对象，把「它」「那」「这个接口」等指代替换为具体名称，保留原问题中的专有名词、字段名与编号。"
            + "只输出改写后的一句问题，不要回答问题，不要解释。如果最新问题已经完整独立，原样输出。";

    /**
     * 改写检索问题。
     *
     * @param conn    对话模型连接参数，非空
     * @param history 本轮之前的对话，按时间升序，元素为 {role, content}；为空时不需要改写，直接返回 null
     * @param query   用户本轮问题，非空
     * @return 改写后的问题；无需改写、调用失败或输出异常时返回 null
     */
    public String rewrite(ModelConnection conn, List<String[]> history, String query) {
        if (history == null || history.isEmpty() || query == null || query.isBlank()) {
            return null;
        }
        String prompt = buildPrompt(history, query);
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(conn.getApiKey())
                .baseUrl(conn.getBaseUrl())
                .timeout(TIMEOUT)
                .maxRetries(0)
                .build();
        try {
            @SuppressWarnings("deprecation")
            ChatCompletion completion = client.chat().completions().create(ChatCompletionCreateParams.builder()
                    .model(conn.getModelName())
                    .addSystemMessage(SYSTEM_PROMPT)
                    .addUserMessage(prompt)
                    .temperature(0)
                    .maxTokens(MAX_TOKENS)
                    .build());
            String output = completion.choices().isEmpty() ? null
                    : completion.choices().get(0).message().content().orElse(null);
            return sanitize(output, query);
        } catch (Exception e) {
            log.warn("[RAG-Rewrite] 查询改写失败，沿用原问题: model={}, reason={}", conn.getModelName(), e.getMessage());
            return null;
        } finally {
            client.close();
        }
    }

    /**
     * 组装改写提示词：对话历史 + 最新问题。
     */
    static String buildPrompt(List<String[]> history, String query) {
        StringBuilder sb = new StringBuilder("对话历史：\n");
        for (String[] msg : history) {
            String role = "assistant".equalsIgnoreCase(msg[0]) ? "助手" : "用户";
            String content = stripNonAnswer(msg[1]);
            if (content.isEmpty()) {
                continue;
            }
            if (content.length() > HISTORY_MESSAGE_MAX) {
                content = content.substring(0, HISTORY_MESSAGE_MAX) + "…";
            }
            sb.append(role).append("：").append(content.replace('\n', ' ')).append('\n');
        }
        sb.append("\n最新问题：").append(query.strip());
        return sb.toString();
    }

    /**
     * 去掉推理块、检索来源块与工具调用摘要行，只保留正文。
     *
     * @param text 原文，可为 null
     * @return 正文，已去除首尾空白
     */
    static String stripNonAnswer(String text) {
        if (text == null) {
            return "";
        }
        String withoutBlocks = NON_ANSWER_BLOCK.matcher(text).replaceAll("");
        return TOOL_LINE.matcher(withoutBlocks).replaceAll("").strip();
    }

    /**
     * 清洗模型输出：去掉前缀与引号，过长或为空时视为无效。
     *
     * @return 清洗后的问题；无效时返回 null
     */
    static String sanitize(String output, String query) {
        if (output == null) {
            return null;
        }
        String text = stripNonAnswer(output);
        // 只取第一行，防止模型附带解释
        int nl = text.indexOf('\n');
        if (nl > 0) {
            text = text.substring(0, nl).strip();
        }
        text = OUTPUT_PREFIX.matcher(text).replaceFirst("");
        text = text.replaceAll("^[\"'“”「『]+|[\"'“”」』]+$", "").strip();
        if (text.isEmpty() || text.length() > query.length() * MAX_LENGTH_FACTOR + MAX_LENGTH_SLACK) {
            return null;
        }
        return text;
    }
}
