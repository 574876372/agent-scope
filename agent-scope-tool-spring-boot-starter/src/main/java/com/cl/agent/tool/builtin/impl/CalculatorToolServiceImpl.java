package com.cl.agent.tool.builtin.impl;

import com.cl.agent.tool.annotation.AgentToolDef;
import com.cl.agent.tool.annotation.AgentToolParam;
import com.cl.agent.tool.builtin.ICalculatorToolService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * {@link ICalculatorToolService} 默认实现，支持四则运算、括号及小数表达式求值。
 */
@Slf4j
@Component
public class CalculatorToolServiceImpl implements ICalculatorToolService {

    /** 计算精度 */
    private static final MathContext MATH_CONTEXT = new MathContext(16, RoundingMode.HALF_UP);

    /**
     * {@inheritDoc}
     */
    @Override
    @AgentToolDef(
            name = "calculate",
            description = "计算数学表达式，支持 +、-、*、/、括号和小数",
            parametersSchema = """
                    {
                      "type": "object",
                      "properties": {
                        "expression": {
                          "type": "string",
                          "description": "数学表达式，如 (1+2)*3/4"
                        }
                      },
                      "required": ["expression"]
                    }
                    """
    )
    public String calculate(
            @AgentToolParam(name = "expression", description = "数学表达式") String expression) {
        if (expression == null || expression.isBlank()) {
            return "错误：表达式不能为空";
        }
        String sanitized = expression.replaceAll("\\s+", "");
        if (!sanitized.matches("[0-9+\\-*/().]+")) {
            return "错误：表达式包含非法字符，仅支持数字和 + - * / ( )";
        }
        try {
            BigDecimal result = evaluate(sanitized);
            String output = result.stripTrailingZeros().toPlainString();
            log.info("[Tool:Calculator] expression={}, result={}", expression, output);
            return output;
        } catch (Exception e) {
            log.warn("[Tool:Calculator] 计算失败, expression={}", expression, e);
            return "计算失败: " + e.getMessage();
        }
    }

    /**
     * 使用双栈法求值数学表达式。
     *
     * @param expression 已去除空格的表达式
     * @return 计算结果
     */
    private BigDecimal evaluate(String expression) {
        Deque<BigDecimal> values = new ArrayDeque<>();
        Deque<Character> ops = new ArrayDeque<>();
        int i = 0;
        while (i < expression.length()) {
            char ch = expression.charAt(i);
            if (ch == '(') {
                ops.push(ch);
                i++;
            } else if (ch == ')') {
                while (!ops.isEmpty() && ops.peek() != '(') {
                    applyTopOperator(values, ops);
                }
                if (ops.isEmpty() || ops.pop() != '(') {
                    throw new IllegalArgumentException("括号不匹配");
                }
                i++;
            } else if (Character.isDigit(ch) || ch == '.') {
                int start = i;
                while (i < expression.length()
                        && (Character.isDigit(expression.charAt(i)) || expression.charAt(i) == '.')) {
                    i++;
                }
                values.push(new BigDecimal(expression.substring(start, i), MATH_CONTEXT));
            } else if (isOperator(ch)) {
                while (!ops.isEmpty() && precedence(ops.peek()) >= precedence(ch)) {
                    applyTopOperator(values, ops);
                }
                ops.push(ch);
                i++;
            } else {
                throw new IllegalArgumentException("无法解析字符: " + ch);
            }
        }
        while (!ops.isEmpty()) {
            applyTopOperator(values, ops);
        }
        if (values.size() != 1) {
            throw new IllegalArgumentException("表达式格式错误");
        }
        return values.pop();
    }

    /**
     * 判断字符是否为四则运算符。
     *
     * @param ch 字符
     * @return 是否为运算符
     */
    private boolean isOperator(char ch) {
        return ch == '+' || ch == '-' || ch == '*' || ch == '/';
    }

    /**
     * 获取运算符优先级。
     *
     * @param op 运算符
     * @return 优先级数值
     */
    private int precedence(char op) {
        if (op == '+' || op == '-') {
            return 1;
        }
        if (op == '*' || op == '/') {
            return 2;
        }
        return 0;
    }

    /**
     * 弹出栈顶运算符并应用到操作数栈。
     *
     * @param values 操作数栈
     * @param ops    运算符栈
     */
    private void applyTopOperator(Deque<BigDecimal> values, Deque<Character> ops) {
        if (values.size() < 2 || ops.isEmpty()) {
            throw new IllegalArgumentException("表达式格式错误");
        }
        char op = ops.pop();
        if (op == '(') {
            ops.push(op);
            return;
        }
        BigDecimal right = values.pop();
        BigDecimal left = values.pop();
        BigDecimal result = switch (op) {
            case '+' -> left.add(right, MATH_CONTEXT);
            case '-' -> left.subtract(right, MATH_CONTEXT);
            case '*' -> left.multiply(right, MATH_CONTEXT);
            case '/' -> {
                if (right.compareTo(BigDecimal.ZERO) == 0) {
                    throw new ArithmeticException("除数不能为零");
                }
                yield left.divide(right, MATH_CONTEXT);
            }
            default -> throw new IllegalArgumentException("未知运算符: " + op);
        };
        values.push(result);
    }
}
