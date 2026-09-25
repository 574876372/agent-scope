package com.cl.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cl.agent.commons.crypto.CryptoService;
import com.cl.agent.dao.AgentInfoMapper;
import com.cl.agent.dao.KnowledgeBaseMapper;
import com.cl.agent.dao.ModelInfoMapper;
import com.cl.agent.dao.ModelProviderMapper;
import com.cl.agent.dto.model.ModelConnection;
import com.cl.agent.dto.model.ModelInfoRequest;
import com.cl.agent.dto.model.ModelProviderRequest;
import com.cl.agent.enums.ModelProtocolEnum;
import com.cl.agent.enums.ModelTypeEnum;
import com.cl.agent.exception.BizException;
import com.cl.agent.model.AgentInfo;
import com.cl.agent.model.KnowledgeBase;
import com.cl.agent.model.ModelInfo;
import com.cl.agent.model.ModelProvider;
import com.cl.agent.service.IModelConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * {@link IModelConfigService} 默认实现。
 *
 * <h2>连接参数指纹</h2>
 * {@link ModelConnection#getVersion()} 取全部连接参数的 SHA-256 摘要，而非更新时间：
 * 通用审计填充器仅在 updateTime 为空时才写入，按实体更新时该字段不会刷新；且内容指纹在多实例部署下天然一致，
 * 任一节点修改配置后，其它节点下次解析即可感知。
 */
@Slf4j
@Service
public class ModelConfigServiceImpl implements IModelConfigService {

    private static final int ENABLED = 1;
    private static final int DISABLED = 0;

    @Autowired
    private ModelProviderMapper providerMapper;

    @Autowired
    private ModelInfoMapper modelMapper;

    @Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Autowired
    private AgentInfoMapper agentInfoMapper;

    /** AES-GCM 加解密服务，由 agent-scope-config 统一注册 */
    @Autowired
    private CryptoService cryptoService;

    // ======================== 厂商 ========================

    /** {@inheritDoc} */
    @Override
    public List<ModelProvider> listProviders() {
        return providerMapper.selectList(new LambdaQueryWrapper<ModelProvider>()
                .orderByAsc(ModelProvider::getCreateTime)
                .orderByAsc(ModelProvider::getCode));
    }

    /** {@inheritDoc} */
    @Override
    public ModelProvider getProvider(String id) {
        return isBlank(id) ? null : providerMapper.selectById(id);
    }

    /** {@inheritDoc} */
    @Override
    public ModelProvider createProvider(ModelProviderRequest request) {
        validateProvider(request);
        if (isBlank(request.getCode())) {
            throw new BizException(400, "厂商编码 code 必填");
        }
        String code = request.getCode().trim();
        if (findProviderByCode(code) != null) {
            throw new BizException(400, "厂商编码已存在: " + code);
        }
        ModelProvider entity = ModelProvider.builder()
                .code(code)
                .name(request.getName().trim())
                .protocol(normalizeProtocol(request.getProtocol()))
                .baseUrl(request.getBaseUrl().trim())
                .apiKeyCipher(isBlank(request.getApiKeyPlain()) ? null : cryptoService.encrypt(request.getApiKeyPlain().trim()))
                .enabled(request.getEnabled() == null ? ENABLED : request.getEnabled())
                .build();
        providerMapper.insert(entity);
        log.info("[ModelConfig] 新增厂商: id={}, code={}, baseUrl={}", entity.getId(), code, entity.getBaseUrl());
        return entity;
    }

    /** {@inheritDoc} */
    @Override
    public ModelProvider updateProvider(ModelProviderRequest request) {
        validateProvider(request);
        ModelProvider existing = requireProvider(request.getId());
        if (!isBlank(request.getCode()) && !existing.getCode().equals(request.getCode().trim())) {
            throw new BizException(400, "厂商编码创建后不可修改");
        }
        boolean disabling = request.getEnabled() != null && request.getEnabled() == DISABLED
                && !Objects.equals(existing.getEnabled(), DISABLED);
        if (disabling) {
            ensureProviderNotInUse(existing, "停用");
        }

        existing.setName(request.getName().trim());
        existing.setProtocol(normalizeProtocol(request.getProtocol()));
        existing.setBaseUrl(request.getBaseUrl().trim());
        if (!isBlank(request.getApiKeyPlain())) {
            existing.setApiKeyCipher(cryptoService.encrypt(request.getApiKeyPlain().trim()));
        }
        if (request.getEnabled() != null) {
            existing.setEnabled(request.getEnabled());
        }
        // 审计填充器仅在字段为空时写入，更新场景需显式刷新
        existing.setUpdateTime(LocalDateTime.now());
        providerMapper.updateById(existing);
        log.info("[ModelConfig] 更新厂商: id={}, code={}, apiKeyChanged={}",
                existing.getId(), existing.getCode(), !isBlank(request.getApiKeyPlain()));
        return existing;
    }

    /** {@inheritDoc} */
    @Override
    public void deleteProvider(String id) {
        ModelProvider existing = requireProvider(id);
        Long modelCount = modelMapper.selectCount(new LambdaQueryWrapper<ModelInfo>()
                .eq(ModelInfo::getProviderId, id));
        if (modelCount > 0) {
            throw new BizException(400, "该厂商下仍有 " + modelCount + " 个模型，请先删除模型");
        }
        ensureProviderNotInUse(existing, "删除");
        providerMapper.deleteById(id);
        log.info("[ModelConfig] 删除厂商: id={}, code={}", id, existing.getCode());
    }

    // ======================== 模型 ========================

    /** {@inheritDoc} */
    @Override
    public List<ModelInfo> listModels(ModelTypeEnum modelType) {
        LambdaQueryWrapper<ModelInfo> w = new LambdaQueryWrapper<>();
        if (modelType != null) {
            w.eq(ModelInfo::getModelType, modelType.name());
        }
        w.orderByAsc(ModelInfo::getCreateTime).orderByAsc(ModelInfo::getModelName);
        return modelMapper.selectList(w);
    }

    /** {@inheritDoc} */
    @Override
    public ModelInfo getModel(String id) {
        return isBlank(id) ? null : modelMapper.selectById(id);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModelInfo createModel(ModelInfoRequest request) {
        ModelTypeEnum type = validateModel(request);
        requireProvider(request.getProviderId());
        ensureModelNameUnique(request.getProviderId(), type, request.getModelName().trim(), null);

        ModelInfo entity = ModelInfo.builder()
                .providerId(request.getProviderId())
                .modelType(type.name())
                .modelName(request.getModelName().trim())
                .dimensions(type == ModelTypeEnum.EMBEDDING ? request.getDimensions() : null)
                .sendDimensions(type == ModelTypeEnum.EMBEDDING ? flag(request.getSendDimensions(), DISABLED) : DISABLED)
                .isDefault(flag(request.getIsDefault(), DISABLED))
                .enabled(flag(request.getEnabled(), ENABLED))
                .build();
        // 同类型尚无默认模型时，新模型自动成为默认
        if (entity.getIsDefault() != ENABLED && findDefaultModel(type) == null) {
            entity.setIsDefault(ENABLED);
        }
        if (entity.getIsDefault() == ENABLED) {
            clearDefault(type);
        }
        modelMapper.insert(entity);
        log.info("[ModelConfig] 新增模型: id={}, type={}, name={}, dimensions={}, default={}",
                entity.getId(), type, entity.getModelName(), entity.getDimensions(), entity.getIsDefault());
        return entity;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModelInfo updateModel(ModelInfoRequest request) {
        ModelTypeEnum type = validateModel(request);
        ModelInfo existing = requireModel(request.getId());
        requireProvider(request.getProviderId());
        String modelName = request.getModelName().trim();
        Integer dimensions = type == ModelTypeEnum.EMBEDDING ? request.getDimensions() : null;
        Integer sendDimensions = type == ModelTypeEnum.EMBEDDING ? flag(request.getSendDimensions(), DISABLED) : DISABLED;
        Integer enabled = flag(request.getEnabled(), existing.getEnabled() == null ? ENABLED : existing.getEnabled());

        long boundKbCount = countBoundKnowledgeBases(existing.getId());
        if (boundKbCount > 0) {
            // 已有知识库用该模型生成了向量，任何影响向量空间或可用性的修改都会导致这些知识库检索失效
            boolean vectorSpaceChanged = !Objects.equals(existing.getProviderId(), request.getProviderId())
                    || !Objects.equals(existing.getModelType(), type.name())
                    || !Objects.equals(existing.getModelName(), modelName)
                    || !Objects.equals(existing.getDimensions(), dimensions)
                    || !Objects.equals(existing.getSendDimensions(), sendDimensions);
            if (vectorSpaceChanged) {
                throw new BizException(400, "该模型已被 " + boundKbCount + " 个知识库绑定，不可修改厂商、类型、模型名与维度");
            }
            if (enabled == DISABLED) {
                throw new BizException(400, "该模型已被 " + boundKbCount + " 个知识库绑定，不可停用");
            }
        }
        ensureModelNameUnique(request.getProviderId(), type, modelName, existing.getId());

        existing.setProviderId(request.getProviderId());
        existing.setModelType(type.name());
        existing.setModelName(modelName);
        existing.setDimensions(dimensions);
        existing.setSendDimensions(sendDimensions);
        existing.setEnabled(enabled);
        if (request.getIsDefault() != null) {
            if (request.getIsDefault() == ENABLED) {
                clearDefault(type);
            }
            existing.setIsDefault(request.getIsDefault());
        }
        existing.setUpdateTime(LocalDateTime.now());
        modelMapper.updateById(existing);
        log.info("[ModelConfig] 更新模型: id={}, type={}, name={}, default={}",
                existing.getId(), type, modelName, existing.getIsDefault());
        return existing;
    }

    /** {@inheritDoc} */
    @Override
    public void deleteModel(String id) {
        ModelInfo existing = requireModel(id);
        long boundKbCount = countBoundKnowledgeBases(id);
        if (boundKbCount > 0) {
            throw new BizException(400, "该模型已被 " + boundKbCount + " 个知识库绑定，不可删除");
        }
        modelMapper.deleteById(id);
        log.info("[ModelConfig] 删除模型: id={}, name={}", id, existing.getModelName());
    }

    /** {@inheritDoc} */
    @Override
    public long countBoundKnowledgeBases(String modelId) {
        if (isBlank(modelId)) {
            return 0L;
        }
        return knowledgeBaseMapper.selectCount(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getEmbeddingModelId, modelId));
    }

    /** {@inheritDoc} */
    @Override
    public ModelInfo requireUsableEmbeddingModel(String modelId) {
        ModelInfo model = isBlank(modelId) ? findDefaultModel(ModelTypeEnum.EMBEDDING) : modelMapper.selectById(modelId);
        if (model == null) {
            throw new BizException(400, isBlank(modelId)
                    ? "尚未配置默认向量模型，请先在模型管理中添加 EMBEDDING 类型的模型"
                    : "向量模型不存在: " + modelId);
        }
        if (!ModelTypeEnum.EMBEDDING.name().equals(model.getModelType())) {
            throw new BizException(400, "所选模型不是向量模型: " + model.getModelName());
        }
        if (!Objects.equals(model.getEnabled(), ENABLED)) {
            throw new BizException(400, "向量模型已停用: " + model.getModelName());
        }
        return model;
    }

    // ======================== 连接解析 ========================

    /** {@inheritDoc} */
    @Override
    public ModelConnection resolveEmbeddingConnection(String modelId) {
        ModelInfo model = requireUsableEmbeddingModel(modelId);
        ModelProvider provider = requireProvider(model.getProviderId());
        ensureProviderEnabled(provider);
        return buildConnection(provider, model, model.getModelName());
    }

    /** {@inheritDoc} */
    @Override
    public ModelConnection resolveChatConnection(String providerCode, String modelName) {
        if (isBlank(providerCode) || isBlank(modelName)) {
            throw new BizException(400, "智能体未配置模型厂商或模型名称");
        }
        ModelProvider provider = findProviderByCode(providerCode.trim());
        if (provider == null) {
            throw new BizException(400, "模型厂商不存在: " + providerCode + "，请在模型管理中添加");
        }
        ensureProviderEnabled(provider);
        ModelInfo model = modelMapper.selectOne(new LambdaQueryWrapper<ModelInfo>()
                .eq(ModelInfo::getProviderId, provider.getId())
                .eq(ModelInfo::getModelType, ModelTypeEnum.CHAT.name())
                .eq(ModelInfo::getModelName, modelName.trim())
                .last("LIMIT 1"));
        return buildConnection(provider, model, modelName.trim());
    }

    /** {@inheritDoc} */
    @Override
    public ModelConnection resolveConnection(String modelId) {
        ModelInfo model = requireModel(modelId);
        ModelProvider provider = requireProvider(model.getProviderId());
        return buildConnection(provider, model, model.getModelName());
    }

    // ======================== 私有方法 ========================

    /**
     * 组装连接参数：解密 API Key 并计算内容指纹。
     *
     * @param provider  厂商
     * @param model     模型，对话模型未登记时可为 null
     * @param modelName 模型名称
     * @return 连接参数
     */
    private ModelConnection buildConnection(ModelProvider provider, ModelInfo model, String modelName) {
        if (isBlank(provider.getApiKeyCipher())) {
            throw new BizException(400, "模型厂商「" + provider.getName() + "」尚未配置 API Key，请在模型管理中补充");
        }
        String apiKey = cryptoService.decrypt(provider.getApiKeyCipher());
        Integer dimensions = model == null ? null : model.getDimensions();
        boolean sendDimensions = model != null && Objects.equals(model.getSendDimensions(), ENABLED);
        String version = fingerprint(provider.getProtocol(), provider.getBaseUrl(), apiKey, modelName,
                String.valueOf(dimensions), String.valueOf(sendDimensions));
        return ModelConnection.builder()
                .modelId(model == null ? null : model.getId())
                .providerCode(provider.getCode())
                .protocol(provider.getProtocol())
                .baseUrl(provider.getBaseUrl())
                .apiKey(apiKey)
                .modelName(modelName)
                .dimensions(dimensions)
                .sendDimensions(sendDimensions)
                .version(version)
                .build();
    }

    /**
     * 厂商停用/删除前的引用检查：被智能体引用，或其向量模型被知识库绑定时拒绝。
     */
    private void ensureProviderNotInUse(ModelProvider provider, String action) {
        Long agentCount = agentInfoMapper.selectCount(new LambdaQueryWrapper<AgentInfo>()
                .eq(AgentInfo::getModelType, provider.getCode()));
        if (agentCount > 0) {
            throw new BizException(400, "该厂商被 " + agentCount + " 个智能体使用，不可" + action);
        }
        List<ModelInfo> embeddingModels = modelMapper.selectList(new LambdaQueryWrapper<ModelInfo>()
                .eq(ModelInfo::getProviderId, provider.getId())
                .eq(ModelInfo::getModelType, ModelTypeEnum.EMBEDDING.name()));
        for (ModelInfo m : embeddingModels) {
            if (countBoundKnowledgeBases(m.getId()) > 0) {
                throw new BizException(400, "该厂商的向量模型 " + m.getModelName() + " 已被知识库绑定，不可" + action);
            }
        }
    }

    private void ensureProviderEnabled(ModelProvider provider) {
        if (!Objects.equals(provider.getEnabled(), ENABLED)) {
            throw new BizException(400, "模型厂商已停用: " + provider.getName());
        }
    }

    private void ensureModelNameUnique(String providerId, ModelTypeEnum type, String modelName, String excludeId) {
        LambdaQueryWrapper<ModelInfo> w = new LambdaQueryWrapper<ModelInfo>()
                .eq(ModelInfo::getProviderId, providerId)
                .eq(ModelInfo::getModelType, type.name())
                .eq(ModelInfo::getModelName, modelName);
        if (excludeId != null) {
            w.ne(ModelInfo::getId, excludeId);
        }
        if (modelMapper.selectCount(w) > 0) {
            throw new BizException(400, "该厂商下已存在同名模型: " + modelName);
        }
    }

    /**
     * 取消同类型全部模型的默认标记。
     */
    private void clearDefault(ModelTypeEnum type) {
        modelMapper.update(null, new LambdaUpdateWrapper<ModelInfo>()
                .eq(ModelInfo::getModelType, type.name())
                .eq(ModelInfo::getIsDefault, ENABLED)
                .set(ModelInfo::getIsDefault, DISABLED));
    }

    private ModelInfo findDefaultModel(ModelTypeEnum type) {
        return modelMapper.selectOne(new LambdaQueryWrapper<ModelInfo>()
                .eq(ModelInfo::getModelType, type.name())
                .eq(ModelInfo::getIsDefault, ENABLED)
                .last("LIMIT 1"));
    }

    private ModelProvider findProviderByCode(String code) {
        return providerMapper.selectOne(new LambdaQueryWrapper<ModelProvider>()
                .eq(ModelProvider::getCode, code)
                .last("LIMIT 1"));
    }

    private ModelProvider requireProvider(String id) {
        ModelProvider provider = getProvider(id);
        if (provider == null) {
            throw new BizException(404, "模型厂商不存在: " + id);
        }
        return provider;
    }

    private ModelInfo requireModel(String id) {
        ModelInfo model = getModel(id);
        if (model == null) {
            throw new BizException(404, "模型不存在: " + id);
        }
        return model;
    }

    private void validateProvider(ModelProviderRequest req) {
        if (req == null) {
            throw new BizException(400, "请求体不能为空");
        }
        if (isBlank(req.getName())) {
            throw new BizException(400, "厂商名称 name 必填");
        }
        if (isBlank(req.getBaseUrl())) {
            throw new BizException(400, "接口地址 baseUrl 必填");
        }
    }

    /**
     * 校验模型请求的公共字段并返回模型类型。
     */
    private ModelTypeEnum validateModel(ModelInfoRequest req) {
        if (req == null) {
            throw new BizException(400, "请求体不能为空");
        }
        if (isBlank(req.getProviderId())) {
            throw new BizException(400, "所属厂商 providerId 必填");
        }
        if (isBlank(req.getModelName())) {
            throw new BizException(400, "模型名称 modelName 必填");
        }
        ModelTypeEnum type = ModelTypeEnum.of(req.getModelType());
        if (type == ModelTypeEnum.EMBEDDING && (req.getDimensions() == null || req.getDimensions() <= 0)) {
            throw new BizException(400, "向量模型必须填写正整数维度 dimensions");
        }
        return type;
    }

    private String normalizeProtocol(String protocol) {
        return isBlank(protocol) ? ModelProtocolEnum.OPENAI.name() : ModelProtocolEnum.of(protocol).name();
    }

    private static int flag(Integer value, int defaultValue) {
        return value == null ? defaultValue : (value == ENABLED ? ENABLED : DISABLED);
    }

    private static String fingerprint(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update(String.valueOf(part).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (Exception e) {
            throw new BizException(500, "计算连接参数指纹失败: " + e.getMessage());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
