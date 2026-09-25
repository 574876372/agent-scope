package com.cl.agent.controller;

import com.cl.agent.biz.IModelConfigBiz;
import com.cl.agent.dto.model.ModelInfoRequest;
import com.cl.agent.dto.model.ModelInfoResponse;
import com.cl.agent.dto.model.ModelProviderRequest;
import com.cl.agent.dto.model.ModelProviderResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 模型配置管理 REST 控制器层。
 * <p>管理模型厂商（接口地址、密钥）与模型（对话模型、向量模型）配置，是系统中模型连接信息的唯一来源。
 * 所有接口均使用明确的固定路径，业务 ID 通过 {@code @RequestParam} 查询参数传递。
 * API Key 只写不读：请求中以明文提交，响应中仅返回是否已配置。</p>
 *
 * <h2>路由一览</h2>
 * <ul>
 *   <li>{@code GET    /api/model-config/providers/list}   — 列出全部厂商</li>
 *   <li>{@code POST   /api/model-config/providers/create} — 新增厂商</li>
 *   <li>{@code PUT    /api/model-config/providers/update} — 更新厂商，?id=</li>
 *   <li>{@code DELETE /api/model-config/providers/delete} — 删除厂商，?id=</li>
 *   <li>{@code GET    /api/model-config/models/list}      — 列出模型，可选 ?modelType=CHAT|EMBEDDING</li>
 *   <li>{@code POST   /api/model-config/models/create}    — 新增模型</li>
 *   <li>{@code PUT    /api/model-config/models/update}    — 更新模型，?id=</li>
 *   <li>{@code DELETE /api/model-config/models/delete}    — 删除模型，?id=</li>
 *   <li>{@code POST   /api/model-config/models/test}      — 测试模型连通性，?id=</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/model-config")
public class ModelConfigController {

    /** 模型配置业务编排层 */
    @Autowired
    private IModelConfigBiz modelConfigBiz;

    /**
     * 列出全部模型厂商（含停用）。
     *
     * @return {@link ResponseEntity} 包含厂商列表，HTTP 状态码 200
     */
    @GetMapping("/providers/list")
    public ResponseEntity<List<ModelProviderResponse>> listProviders() {
        return ResponseEntity.ok(modelConfigBiz.listProviders());
    }

    /**
     * 新增模型厂商，API Key 加密存储。
     *
     * @param request 创建请求体，{@code code}、{@code name}、{@code baseUrl} 必填
     * @return {@link ResponseEntity} 包含创建后的厂商详情（不含密钥），HTTP 状态码 200
     */
    @PostMapping("/providers/create")
    public ResponseEntity<ModelProviderResponse> createProvider(@RequestBody ModelProviderRequest request) {
        return ResponseEntity.ok(modelConfigBiz.createProvider(request));
    }

    /**
     * 更新模型厂商；{@code apiKeyPlain} 留空表示不修改密钥，{@code code} 不可修改。
     *
     * @param id      厂商 ID，通过查询参数 {@code ?id=} 传入
     * @param request 更新请求体
     * @return {@link ResponseEntity} 包含更新后的厂商详情（不含密钥），HTTP 状态码 200
     */
    @PutMapping("/providers/update")
    public ResponseEntity<ModelProviderResponse> updateProvider(@RequestParam("id") String id,
                                                                @RequestBody ModelProviderRequest request) {
        return ResponseEntity.ok(modelConfigBiz.updateProvider(id, request));
    }

    /**
     * 删除模型厂商；其下仍有模型或被智能体使用时拒绝。
     *
     * @param id 厂商 ID，通过查询参数 {@code ?id=} 传入
     * @return {@link ResponseEntity} 无返回值，HTTP 状态码 204
     */
    @DeleteMapping("/providers/delete")
    public ResponseEntity<Void> deleteProvider(@RequestParam("id") String id) {
        modelConfigBiz.deleteProvider(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 列出模型。
     *
     * @param modelType 可选类型过滤：CHAT / EMBEDDING
     * @return {@link ResponseEntity} 包含模型列表，HTTP 状态码 200
     */
    @GetMapping("/models/list")
    public ResponseEntity<List<ModelInfoResponse>> listModels(@RequestParam(value = "modelType", required = false) String modelType) {
        return ResponseEntity.ok(modelConfigBiz.listModels(modelType));
    }

    /**
     * 新增模型。
     *
     * @param request 创建请求体，{@code providerId}、{@code modelType}、{@code modelName} 必填；向量模型还需 {@code dimensions}
     * @return {@link ResponseEntity} 包含创建后的模型详情，HTTP 状态码 200
     */
    @PostMapping("/models/create")
    public ResponseEntity<ModelInfoResponse> createModel(@RequestBody ModelInfoRequest request) {
        return ResponseEntity.ok(modelConfigBiz.createModel(request));
    }

    /**
     * 更新模型；已被知识库绑定的向量模型不可修改模型名与维度。
     *
     * @param id      模型 ID，通过查询参数 {@code ?id=} 传入
     * @param request 更新请求体
     * @return {@link ResponseEntity} 包含更新后的模型详情，HTTP 状态码 200
     */
    @PutMapping("/models/update")
    public ResponseEntity<ModelInfoResponse> updateModel(@RequestParam("id") String id,
                                                         @RequestBody ModelInfoRequest request) {
        return ResponseEntity.ok(modelConfigBiz.updateModel(id, request));
    }

    /**
     * 删除模型；已被知识库绑定时拒绝。
     *
     * @param id 模型 ID，通过查询参数 {@code ?id=} 传入
     * @return {@link ResponseEntity} 无返回值，HTTP 状态码 204
     */
    @DeleteMapping("/models/delete")
    public ResponseEntity<Void> deleteModel(@RequestParam("id") String id) {
        modelConfigBiz.deleteModel(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 测试模型连通性：向量模型会校验返回维度与配置一致。
     *
     * @param id 模型 ID，通过查询参数 {@code ?id=} 传入
     * @return {@link ResponseEntity} 包含 {@code {"success": boolean, "message": string}}，HTTP 状态码 200
     */
    @PostMapping("/models/test")
    public ResponseEntity<Map<String, Object>> testModel(@RequestParam("id") String id) {
        return ResponseEntity.ok(modelConfigBiz.testModel(id));
    }
}
