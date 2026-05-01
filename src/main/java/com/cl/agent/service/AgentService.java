package com.cl.agent.service;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AgentService {

    private final Map<String, Map<String, Object>> agents = new ConcurrentHashMap<>();

    public List<Map<String, Object>> getAllAgents() {
        return new ArrayList<>(agents.values());
    }

    public Map<String, Object> getAgent(String id) {
        return agents.get(id);
    }

    public Map<String, Object> createAgent(Map<String, Object> agentData) {
        try {
            String id = UUID.randomUUID().toString();
            Map<String, Object> agent = new HashMap<>();
            agent.put("id", id);
            agent.put("name", agentData.getOrDefault("name", "Unnamed Agent"));
            agent.put("description", agentData.getOrDefault("description", ""));
            agent.put("type", agentData.getOrDefault("type", "general"));
            agent.put("status", "active");
            agent.put("createdAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            agent.put("updatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

            // 添加其他可选字段
            agentData.forEach((key, value) -> {
                if (!agent.containsKey(key)) {
                    agent.put(key, value);
                }
            });

            agents.put(id, agent);
            return agent;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create agent: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> updateAgent(String id, Map<String, Object> agentData) {
        try {
            Map<String, Object> existingAgent = agents.get(id);
            if (existingAgent == null) {
                return null;
            }

            // 更新字段
            agentData.forEach((key, value) -> {
                if (!"id".equals(key) && !"createdAt".equals(key)) {
                    existingAgent.put(key, value);
                }
            });

            existingAgent.put("updatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

            return existingAgent;
        } catch (Exception e) {
            throw new RuntimeException("Failed to update agent: " + e.getMessage(), e);
        }
    }

    public boolean deleteAgent(String id) {
        return agents.remove(id) != null;
    }

    public Map<String, Object> executeTask(String id, Map<String, Object> taskData) {
        try {
            Map<String, Object> agent = agents.get(id);
            if (agent == null) {
                throw new RuntimeException("Agent not found: " + id);
            }

            String taskType = (String) taskData.getOrDefault("taskType", "general");
            String taskInput = (String) taskData.getOrDefault("input", "");

            // 模拟任务执行
            Map<String, Object> result = new HashMap<>();
            result.put("taskId", UUID.randomUUID().toString());
            result.put("agentId", id);
            result.put("taskType", taskType);
            result.put("input", taskInput);
            result.put("status", "completed");
            result.put("executedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

            // 根据任务类型生成模拟结果
            String output = generateTaskOutput(taskType, taskInput);
            result.put("output", output);

            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute task: " + e.getMessage(), e);
        }
    }

    private String generateTaskOutput(String taskType, String input) {
        switch (taskType.toLowerCase()) {
            case "text_analysis":
                return "文本分析完成。输入文本长度: " + input.length() + " 字符。分析结果: 这是一个" +
                       (input.length() > 100 ? "较长" : "较短") + "的文本。";

            case "data_processing":
                return "数据处理完成。处理了 " + input.split(",").length + " 个数据项。";

            case "question_answering":
                return "基于我的知识库，对于问题'" + input + "'，我认为这是一个很好的问题。答案是：这取决于具体情况。";

            case "code_generation":
                return "// 生成的代码示例\npublic class Example {\n    public static void main(String[] args) {\n        System.out.println(\"Hello, " + input + "!\");\n    }\n}";

            case "translation":
                return "翻译结果: " + input + " (这是模拟翻译，实际使用时请集成翻译服务)";

            default:
                return "任务 '" + taskType + "' 执行完成。输入: " + input + "。这是模拟结果。";
        }
    }
}
