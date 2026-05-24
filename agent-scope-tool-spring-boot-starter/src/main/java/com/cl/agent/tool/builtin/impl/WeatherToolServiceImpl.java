package com.cl.agent.tool.builtin.impl;

import com.cl.agent.tool.annotation.AgentToolDef;
import com.cl.agent.tool.annotation.AgentToolParam;
import com.cl.agent.tool.builtin.IWeatherToolService;
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
 * {@link IWeatherToolService} 默认实现，通过 wttr.in 免费 API 查询城市天气。
 */
@Slf4j
@Component
public class WeatherToolServiceImpl implements IWeatherToolService {

    /** HTTP 客户端，用于调用 wttr.in */
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build();

    /**
     * {@inheritDoc}
     */
    @Override
    @AgentToolDef(
            name = "get_weather",
            description = "查询指定城市的当前天气信息，包括温度、天气状况等",
            parametersSchema = """
                    {
                      "type": "object",
                      "properties": {
                        "city": {
                          "type": "string",
                          "description": "城市名称，如 Beijing、上海"
                        }
                      },
                      "required": ["city"]
                    }
                    """
    )
    public String getWeather(
            @AgentToolParam(name = "city", description = "城市名称") String city) {
        if (city == null || city.isBlank()) {
            return "错误：城市名称不能为空";
        }
        String encodedCity = URLEncoder.encode(city.trim(), StandardCharsets.UTF_8);
        // format 说明: %l=城市, %C=天气描述, %t=温度, %h=湿度, %w=风速
        // 参数 m=公制单位(摄氏度), lang=zh=中文天气描述
        String url = "https://wttr.in/" + encodedCity + "?format=%l:+%C,+温度+%t,+湿度+%h,+风速+%w&m&lang=zh";
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "agent-scope/1.0")
                .header("Accept-Language", "zh-CN")
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return "天气查询失败，HTTP 状态: " + response.code();
            }
            String body = response.body().string().trim();
            log.info("[Tool:Weather] city={}, result={}", city, body);
            return body;
        } catch (IOException e) {
            log.warn("[Tool:Weather] 查询失败, city={}", city, e);
            return "天气查询失败: " + e.getMessage();
        }
    }
}
