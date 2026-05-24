package com.cl.agent.tool.builtin;

/**
 * 数学表达式计算工具契约，供 Agent 通过 {@code calculate} 调用。
 */
public interface ICalculatorToolService {

    /**
     * 计算数学表达式并返回结果。
     *
     * @param expression 数学表达式，如 {@code (1+2)*3/4}
     * @return 计算结果字符串；参数非法或计算失败时返回可读错误说明
     */
    String calculate(String expression);
}
