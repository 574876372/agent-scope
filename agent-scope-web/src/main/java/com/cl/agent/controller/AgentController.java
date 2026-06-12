package com.cl.agent.controller;

import com.cl.agent.dto.AgentResponse;
import com.cl.agent.dto.ChatRequest;
import com.cl.agent.dto.ChatResponse;
import com.cl.agent.dto.CreateAgentRequest;
import com.cl.agent.biz.IAgentBiz;
import com.cl.agent.enums.ModelProviderEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agent 智能体管理 REST 控制器层。
 * <p>提供智能体的完整生命周期管理（创建、查询、更新、删除）以及同步对话能力接入。
 * 所有接口均使用明确的固定路径，参数通过 {@code @RequestParam} 查询参数传递，
 * 禁止使用 {@code {pathVariable}} 动态路径变量方式传递业务 ID。
 * 遵循严格的层级边界规范，仅调用 {@link IAgentBiz} 业务编排层。</p>
 *
 * <h2>路由一览</h2>
 * <ul>
 *   <li>{@code GET    /api/agents/models}   — 获取支持的模型厂商及其模型列表</li>
 *   <li>{@code POST   /api/agents/create}   — 创建智能体</li>
 *   <li>{@code GET    /api/agents/list}     — 列出当前用户的所有智能体</li>
 *   <li>{@code GET    /api/agents/detail}   — 获取单个智能体详情，?id=</li>
 *   <li>{@code DELETE /api/agents/delete}   — 删除智能体，?id=</li>
 *   <li>{@code PUT    /api/agents/update}   — 更新智能体，?id=</li>
 *   <li>{@code POST   /api/agents/chat}     — 向指定智能体发送同步消息，?id=</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/agents")
@CrossOrigin(origins = "*")
public class AgentController {

    @Autowired
    private IAgentBiz agentBiz;

    /**
     * 获取系统支持的全部模型厂商及其对应的可选模型列表。
     * <p>使用说明：由前端创建/编辑智能体的模型选择下拉框初始化时调用；返回数据来自枚举定义，无需鉴权。</p>
     *
     * @return {@link ResponseEntity} 包含厂商列表（每项含 {@code type} 与 {@code models} 字段），HTTP 状态码 200
     */
    @GetMapping("/models")
    public ResponseEntity<List<Map<String, Object>>> getModelProviders() {
        List<Map<String, Object>> providers = Arrays.stream(ModelProviderEnum.values())
                .map(p -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("type", p.getType());
                    map.put("models", p.getModels());
                    return map;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(providers);
    }

    /**
     * 创建一个新的智能体，并持久化其配置元数据。
     * <p>使用说明：由前端"新建智能体"表单提交；当前登录用户 ID 由业务层从上下文自动读取。</p>
     *
     * @param request 创建智能体参数体，{@code name}、{@code modelType} 必填，非空
     * @return {@link ResponseEntity} 包含创建成功的智能体响应对象，HTTP 状态码 200
     */
    @PostMapping("/create")
    public ResponseEntity<AgentResponse> createAgent(@RequestBody CreateAgentRequest request) {
        return ResponseEntity.ok(agentBiz.createAgent(request));
    }

    /**
     * 列出当前登录用户创建的所有活跃智能体。
     *
     * @return {@link ResponseEntity} 包含智能体响应列表，HTTP 状态码 200；无数据时返回空列表
     */
    @GetMapping("/list")
    public ResponseEntity<List<AgentResponse>> listAgents() {
        return ResponseEntity.ok(agentBiz.listAgents());
    }

    /**
     * 获取指定 ID 智能体的详细配置信息。
     * <p>使用说明：由前端智能体详情页或编辑页调用，?id= 传入智能体唯一 ID。</p>
     *
     * @param id 智能体唯一 ID，非空，通过查询参数 {@code ?id=} 传入
     * @return {@link ResponseEntity} 包含智能体详情响应，HTTP 状态码 200
     * @throws com.cl.agent.exception.BizException 当智能体不存在时，HTTP 404
     */
    @GetMapping("/detail")
    public ResponseEntity<AgentResponse> getAgent(@RequestParam("id") String id) {
        return ResponseEntity.ok(agentBiz.getAgent(id));
    }

    /**
     * 删除指定 ID 的智能体，清理其运行时缓存与持久化数据。
     * <p>使用说明：由前端"删除智能体"确认弹窗提交，?id= 传入目标智能体 ID；操作不可逆。</p>
     *
     * @param id 待删除智能体的唯一 ID，非空，通过查询参数 {@code ?id=} 传入
     * @return {@link ResponseEntity} 无返回值，HTTP 状态码 204
     */
    @DeleteMapping("/delete")
    public ResponseEntity<Void> deleteAgent(@RequestParam("id") String id) {
        agentBiz.deleteAgent(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 更新指定 ID 智能体的配置信息（含工具绑定、知识库绑定等）。
     * <p>使用说明：由前端"编辑智能体"表单提交，?id= 传入目标智能体 ID，请求体携带完整的更新配置。</p>
     *
     * @param id      待更新智能体的唯一 ID，非空，通过查询参数 {@code ?id=} 传入
     * @param request 更新参数体，字段与创建时一致，全量覆盖
     * @return {@link ResponseEntity} 包含更新后的智能体响应对象，HTTP 状态码 200
     */
    @PutMapping("/update")
    public ResponseEntity<AgentResponse> updateAgent(@RequestParam("id") String id,
                                                     @RequestBody CreateAgentRequest request) {
        return ResponseEntity.ok(agentBiz.updateAgent(id, request));
    }

    /**
     * 向指定智能体发送同步对话消息，等待 Agent 完整推理后返回结果。
     * <p>使用说明：适合短消息场景；长推理或工具调用建议改用 SSE 流式接口；
     * ?id= 传入目标智能体 ID，请求体携带消息内容与会话信息。</p>
     *
     * @param id      目标智能体的唯一 ID，非空，通过查询参数 {@code ?id=} 传入
     * @param request 对话请求体，{@code content} 必填，{@code conversationId} 可为空
     * @return {@link ResponseEntity} 包含 Agent 回复内容的响应对象，HTTP 状态码 200
     */
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestParam("id") String id, @RequestBody ChatRequest request) {
        return ResponseEntity.ok(agentBiz.chat(id, request));
    }
}
