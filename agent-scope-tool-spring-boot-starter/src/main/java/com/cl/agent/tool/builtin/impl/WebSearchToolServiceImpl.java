package com.cl.agent.tool.builtin.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.cl.agent.tool.annotation.AgentToolDef;
import com.cl.agent.tool.annotation.AgentToolParam;
import com.cl.agent.tool.builtin.IWebSearchToolService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * {@link IWebSearchToolService} 默认实现，通过 DuckDuckGo Instant Answer API 检索摘要。
 */
@Slf4j
@Component
public class WebSearchToolServiceImpl implements IWebSearchToolService {

    /** HTTP 客户端 */
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build();

    /**
     * {@inheritDoc}
     */
    @Override
    @AgentToolDef(
            name = "web_search",
            description = "搜索互联网信息，返回与查询相关的摘要内容",
            parametersSchema = """
                    {
                      "type": "object",
                      "properties": {
                        "query": {
                          "type": "string",
                          "description": "搜索关键词或问题"
                        }
                      },
                      "required": ["query"]
                    }
                    """
    )
    public String webSearch(
            @AgentToolParam(name = "query", description = "搜索关键词") String query) {
        if (query == null || query.isBlank()) {
            return "错误：搜索关键词不能为空";
        }
        String encodedQuery = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
        String url = "https://api.duckduckgo.com/?q=" + encodedQuery + "&format=json&no_html=1&skip_disambig=1";
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "agent-scope/1.0")
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return "搜索失败，HTTP 状态: " + response.code();
            }
            String body = response.body().string();
            String summary = parseDuckDuckGoResponse(body, query);
            log.info("[Tool:WebSearch] query={}, summaryLength={}", query, summary.length());
            return summary;
        } catch (IOException e) {
            log.warn("[Tool:WebSearch] 搜索失败, query={}", query, e);
            return "搜索失败: " + e.getMessage();
        }
    }

    /**
     * 解析 DuckDuckGo JSON 响应，提取 Abstract 或 RelatedTopics。
     *
     * @param jsonBody API 响应 JSON
     * @param query    原始查询词
     * @return 可读摘要文本
     */
    private String parseDuckDuckGoResponse(String jsonBody, String query) {
        JSONObject json = JSON.parseObject(jsonBody);
        String abstractText = json.getString("AbstractText");
        if (abstractText != null && !abstractText.isBlank()) {
            String source = json.getString("AbstractSource");
            String url = json.getString("AbstractURL");
            StringBuilder sb = new StringBuilder(abstractText);
            if (source != null && !source.isBlank()) {
                sb.append("\n来源: ").append(source);
            }
            if (url != null && !url.isBlank()) {
                sb.append("\n链接: ").append(url);
            }
            return sb.toString();
        }

        JSONArray related = json.getJSONArray("RelatedTopics");
        if (related != null && !related.isEmpty()) {
            StringBuilder sb = new StringBuilder("与「").append(query).append("」相关的搜索结果：\n");
            int count = 0;
            for (int i = 0; i < related.size() && count < 5; i++) {
                JSONObject item = related.getJSONObject(i);
                if (item == null) {
                    continue;
                }
                String text = item.getString("Text");
                if (text != null && !text.isBlank()) {
                    sb.append("- ").append(text).append('\n');
                    count++;
                }
            }
            if (count > 0) {
                return sb.toString().trim();
            }
        }
        return "未找到与「" + query + "」相关的摘要信息，请尝试更换关键词。";
    }
}
