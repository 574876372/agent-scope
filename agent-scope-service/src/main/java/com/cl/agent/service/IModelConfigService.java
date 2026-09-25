package com.cl.agent.service;

import com.cl.agent.dto.model.ModelConnection;
import com.cl.agent.dto.model.ModelInfoRequest;
import com.cl.agent.dto.model.ModelProviderRequest;
import com.cl.agent.enums.ModelTypeEnum;
import com.cl.agent.model.ModelInfo;
import com.cl.agent.model.ModelProvider;

import java.util.List;

/**
 * 模型配置管理服务接口。
 *
 * <p>对应 {@code t_model_provider}（厂商连接信息）与 {@code t_model}（模型定义）两张表，是对话模型与向量模型
 * 连接信息的唯一来源：
 * <ul>
 *   <li>厂商 / 模型的 CRUD，API Key 以 AES-GCM 加密落库；</li>
 *   <li>保护规则：被知识库绑定的向量模型不可修改模型名与维度、不可停用或删除；
 *       被智能体引用的厂商不可停用或删除；</li>
 *   <li>{@link #resolveEmbeddingConnection} / {@link #resolveChatConnection}：解密密钥并组装
 *       {@link ModelConnection}，供业务层构建模型客户端。</li>
 * </ul>
 * 模型配置为系统级全局配置，不做多租户隔离。</p>
 */
public interface IModelConfigService {

    // ======================== 厂商 ========================

    /**
     * 列出全部厂商（含停用），按创建时间正序。
     *
     * @return 厂商列表，可能为空列表但不为 null
     */
    List<ModelProvider> listProviders();

    /**
     * 按主键查询厂商。
     *
     * @param id 厂商 ID
     * @return 厂商实体，不存在时返回 null
     */
    ModelProvider getProvider(String id);

    /**
     * 新增厂商；apiKeyPlain 非空时加密落库。
     *
     * @param request 新增参数，code / name / baseUrl 必填
     * @return 持久化后的实体
     */
    ModelProvider createProvider(ModelProviderRequest request);

    /**
     * 更新厂商；code 不可修改，apiKeyPlain 为空表示不修改密钥。
     *
     * @param request 更新参数，id 必填
     * @return 更新后的实体
     */
    ModelProvider updateProvider(ModelProviderRequest request);

    /**
     * 软删除厂商；其下仍有模型或被智能体引用时拒绝。
     *
     * @param id 厂商 ID
     */
    void deleteProvider(String id);

    // ======================== 模型 ========================

    /**
     * 列出模型，按创建时间正序。
     *
     * @param modelType 模型类型过滤，null 表示全部
     * @return 模型列表，可能为空列表但不为 null
     */
    List<ModelInfo> listModels(ModelTypeEnum modelType);

    /**
     * 按主键查询模型。
     *
     * @param id 模型 ID
     * @return 模型实体，不存在时返回 null
     */
    ModelInfo getModel(String id);

    /**
     * 新增模型；设为默认时会取消同类型其它模型的默认标记，同类型尚无默认模型时自动设为默认。
     *
     * @param request 新增参数
     * @return 持久化后的实体
     */
    ModelInfo createModel(ModelInfoRequest request);

    /**
     * 更新模型；被知识库绑定的向量模型仅允许修改默认标记。
     *
     * @param request 更新参数，id 必填
     * @return 更新后的实体
     */
    ModelInfo updateModel(ModelInfoRequest request);

    /**
     * 软删除模型；被知识库绑定时拒绝。
     *
     * @param id 模型 ID
     */
    void deleteModel(String id);

    /**
     * 统计绑定指定向量模型的知识库数量。
     *
     * @param modelId 模型 ID
     * @return 知识库数量
     */
    long countBoundKnowledgeBases(String modelId);

    /**
     * 校验并返回可用于创建知识库的向量模型。
     *
     * @param modelId 指定的模型 ID；为空时取默认向量模型
     * @return 已启用的向量模型
     */
    ModelInfo requireUsableEmbeddingModel(String modelId);

    // ======================== 连接解析 ========================

    /**
     * 解析向量模型的完整连接参数（含解密后的 API Key）。
     *
     * @param modelId 向量模型 ID；为空时取默认向量模型
     * @return 连接参数
     */
    ModelConnection resolveEmbeddingConnection(String modelId);

    /**
     * 解析对话模型的完整连接参数（含解密后的 API Key）。
     * <p>智能体按"厂商编码 + 模型名"引用对话模型，模型名无需在模型表中登记。</p>
     *
     * @param providerCode 厂商编码，对应智能体的 modelType
     * @param modelName    模型名称
     * @return 连接参数
     */
    ModelConnection resolveChatConnection(String providerCode, String modelName);

    /**
     * 解析指定模型（任意类型）的完整连接参数，供"测试连接"使用。
     *
     * @param modelId 模型 ID
     * @return 连接参数
     */
    ModelConnection resolveConnection(String modelId);
}
