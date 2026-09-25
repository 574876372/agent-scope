package com.cl.agent.biz;

import com.cl.agent.dto.model.ModelInfoRequest;
import com.cl.agent.dto.model.ModelInfoResponse;
import com.cl.agent.dto.model.ModelProviderRequest;
import com.cl.agent.dto.model.ModelProviderResponse;

import java.util.List;
import java.util.Map;

/**
 * 模型配置管理业务编排接口。
 * <p>负责厂商与模型的 CRUD、实体到响应 DTO 的转换、配置变更后的缓存失效通知，以及模型连通性测试。</p>
 */
public interface IModelConfigBiz {

    List<ModelProviderResponse> listProviders();

    ModelProviderResponse createProvider(ModelProviderRequest request);

    ModelProviderResponse updateProvider(String id, ModelProviderRequest request);

    void deleteProvider(String id);

    /**
     * 列出模型。
     *
     * @param modelType 类型过滤（CHAT / EMBEDDING），为空表示全部
     * @return 模型列表
     */
    List<ModelInfoResponse> listModels(String modelType);

    ModelInfoResponse createModel(ModelInfoRequest request);

    ModelInfoResponse updateModel(String id, ModelInfoRequest request);

    void deleteModel(String id);

    /**
     * 测试已保存模型的连通性。
     * <p>向量模型实际调用一次 embed 并校验返回维度与配置一致；对话模型发送一次最小请求。</p>
     *
     * @param id 模型 ID
     * @return {@code success}（是否通过）与 {@code message}（结果说明）
     */
    Map<String, Object> testModel(String id);

    /**
     * 智能体创建页的对话模型下拉选项：已启用厂商及其已启用的对话模型。
     *
     * @return 每项含 {@code type}（厂商编码）、{@code name}（厂商名称）、{@code models}（模型名列表）
     */
    List<Map<String, Object>> listChatModelOptions();
}
