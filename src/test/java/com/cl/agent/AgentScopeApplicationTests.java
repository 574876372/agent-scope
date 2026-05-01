package com.cl.agent;

import com.cl.agent.service.DemoServe;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.OpenAIChatModel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
class AgentScopeApplicationTests {

    @Test
    void contextLoads() {
    }

    @Test
    void testLesson01_DemoServeDefaultCall() {
        // 1. 创建你的自定义模型 (复用 DemoServe 中的方法)
        OpenAIChatModel model = DemoServe.createMyModel();

        // 2. 创建 Agent
        Agent agent = ReActAgent.builder()
                .name("my_agent")
                .model(model)
                .build();

        // 3. 发送消息并收集结果
        MsgRole role = MsgRole.USER;
        Msg response = agent.call(Msg.builder().textContent("你好，这是我的第一个AgentScope测试用例！").role(role).build()).block();

        // 4. 打印并验证返回结果
        System.out.println("【测试案例1输出】: " + response.getTextContent());
        Assertions.assertNotNull(response.getTextContent(), "AI返回内容不应为空");
    }

    @Test
    void testLesson02_DemoServeStreamCall() {
        // 1. 使用 DeepSeek 官方接口测试流式输出
        OpenAIChatModel streamModel = OpenAIChatModel.builder()
                .baseUrl("https://api.deepseek.com/v1")
                .apiKey("sk-3133ef74c49c44ddbfc230343f38d4f0")
                .modelName("deepseek-chat")
                .stream(true) // 开启底层 SSE 流式请求
                .build();

        // 2. 创建 Agent
        Agent agent = ReActAgent.builder()
                .name("stream_agent")
                .model(streamModel)
                .build();

        // 3. 准备消息
        Msg requestMsg = Msg.builder()
                .textContent("请用大约50字描述一下未来的智能体 (Agent)。")
                .role(MsgRole.USER)
                .build();

        // 4. 使用 agent.stream() —— 返回 Flux<Event>，每个 chunk emit 一次
        // ❌ agent.call() 返回 Mono<Msg>，框架内部拼完所有 chunk 才 emit，永远只触发1次
        // ✅ agent.stream() 返回 Flux<Event>，真正逐 token emit
        AtomicInteger count = new AtomicInteger(0);

        System.out.println("【测试案例2-DeepSeek 流式拉取中】:");
        agent.stream(requestMsg)
                .doOnNext(event -> {
                    String chunk = event.getMessage() != null ? event.getMessage().getTextContent() : "";
                    int i = count.incrementAndGet();
                    System.out.printf("[第%d次|type=%s|last=%s] %s%n",
                            i, event.getType(), event.isLast(), chunk);
                    System.out.flush();
                })
                .blockLast();

        System.out.println("【总共触发 doOnNext 次数】: " + count.get());

        // 5. 断言：真正的流式响应应多次触发，每次携带一小段 token
        Assertions.assertTrue(count.get() > 1,
                "stream() 应多次触发 doOnNext，实际触发次数: " + count.get());
    }

    @Test
    void testLesson03_EnableThinkingMode() {
        // 1. 创建你的自定义模型 并启用思考模式
        OpenAIChatModel model = OpenAIChatModel.builder()
                .baseUrl("https://api.deepseek.com/v1")
                .apiKey("sk-3133ef74c49c44ddbfc230343f38d4f0")
                .modelName("deepseek-chat")
                .build();
        // 2. 创建 Agent
        Agent agent = ReActAgent.builder()
                .name("thinking_agent")
                .model(model)
                .build();

        // 3. 发送消息并收集结果
        MsgRole role = MsgRole.USER;
        Msg response = agent.call(Msg.builder().textContent("请解释一下什么是人工智能，并展示你的推理过程。").role(role).build()).block();

        // 4. 打印并验证返回结果
        String fullResponse = response.getTextContent();
        System.out.println("完整输出开始");
        System.out.println("【完整输出】: " + fullResponse);
        System.out.println("完整输出结束");

        // 解析思考过程和结果（假设思考过程在 <think> 和 </think> 之间）
        int start = fullResponse.indexOf("<think>");
        int end = fullResponse.indexOf("</think>");
        if (start != -1 && end != -1 && end > start) {
            String thinking = fullResponse.substring(start + 7, end);
            String result = fullResponse.substring(end + 8).trim();
            System.out.println("思考过程 开始");
            System.out.println("【思考过程】: " + thinking);
            System.out.println("思考过程 结束");
            System.out.println("【结果开始】: " + result);
            System.out.println("【结果】: " + result);
            System.out.println("【结果结束】: " + result);
            Assertions.assertNotNull(thinking, "思考过程不应为空");
            Assertions.assertNotNull(result, "结果不应为空");
        } else {
            System.out.println("【结果】: " + fullResponse);
            Assertions.assertNotNull(fullResponse, "AI返回内容不应为空");
        }
    }

}
