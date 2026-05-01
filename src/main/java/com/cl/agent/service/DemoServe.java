package com.cl.agent.service;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.OpenAIChatModel;

public class DemoServe {

    /**
     * 千问
     * @return
     */
    public static OpenAIChatModel createMyModel() {
        // 如果模型服务需要 API Key，在此设置；如果不需要，可以留空或填任意值
        String myApiKey = "sk-c95e548188d16bed66cc5e5880969f056a3ffeea9dc976b4";

        // 指定你要使用的具体模型名称
        String myModelName = "Qwen3.5-27B";

        return OpenAIChatModel.builder()
                .baseUrl("https://aiapi.oldbird.tech/v1")
                .modelName(myModelName)
                .apiKey(myApiKey)
                .build();
    }

    /**
     * DeepSeek
     * @return
     */
    public static OpenAIChatModel createDeepseek(){
        // deepseek 模型
        OpenAIChatModel model = OpenAIChatModel.builder()
                .baseUrl("https://api.deepseek.com/v1")  // 设置 DeepSeek API 地址
                .apiKey("sk-3133ef74c49c44ddbfc230343f38d4f0")          // 替换为你的 API Key
                .modelName("deepseek-chat")               // 或 "deepseek-reasoner"
                .build();
        return model;
    }




    public  static void main(String[] args){
        // 1. 创建你的自定义模型
        OpenAIChatModel OpenAIChatModel = DemoServe.createMyModel();

        // 创建 Agent 并执行对话
        Agent agent = ReActAgent.builder()
                .name("my_agent")
                .model(OpenAIChatModel)
                .build();
        // 发送消息并获取回复
        MsgRole role = MsgRole.USER;
        Msg response = agent.call(Msg.builder().textContent("你好，DeepSeek！").role(role).build()).block();
        System.out.println(response.getTextContent());
    }
}
