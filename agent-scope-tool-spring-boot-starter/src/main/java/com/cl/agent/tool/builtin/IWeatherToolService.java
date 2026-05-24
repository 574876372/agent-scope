package com.cl.agent.tool.builtin;

/**
 * 天气查询工具契约，供 Agent 通过 {@code get_weather} 调用。
 */
public interface IWeatherToolService {

    /**
     * 查询指定城市的当前天气。
     *
     * @param city 城市名称（中文或英文均可）
     * @return 天气描述文本；参数非法或调用失败时返回可读错误说明
     */
    String getWeather(String city);
}
