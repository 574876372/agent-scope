package com.cl.agent.rag;

import com.cl.agent.rag.core.EmbeddingModelSpec;
import com.cl.agent.rag.core.EmbeddingStoreFactory;
import com.cl.agent.rag.properties.AgentRagProperties;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.rag.store.InMemoryStore;
import io.agentscope.core.rag.store.VDBStoreBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link EmbeddingStoreFactory} 单元测试：验证按 spec 缓存的 Embedding 客户端与按 kbId 缓存的存储实例语义。
 * <p>全部用例使用 IN_MEMORY 存储，不依赖外部服务；Embedding 客户端仅构建不发起网络请求。</p>
 */
public class EmbeddingStoreFactoryTest {

    private AgentRagProperties properties;

    private EmbeddingStoreFactory factory;

    @BeforeEach
    public void setUp() {
        properties = new AgentRagProperties();
        properties.setStoreType("IN_MEMORY");
        factory = new EmbeddingStoreFactory(properties);
    }

    @AfterEach
    public void tearDown() {
        factory.close();
    }

    private EmbeddingModelSpec.EmbeddingModelSpecBuilder spec() {
        return EmbeddingModelSpec.builder()
                .modelId("model-a")
                .protocol("OPENAI")
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                .apiKey("test-key")
                .modelName("text-embedding-v2")
                .dimensions(768)
                .version("v1");
    }

    @Test
    public void storeShouldBeCachedPerKbId() {
        VDBStoreBase first = factory.getStore("kb-a", 768);
        VDBStoreBase again = factory.getStore("kb-a", 768);
        VDBStoreBase other = factory.getStore("kb-b", 768);

        assertSame(first, again, "同一 kbId 应返回同一存储实例，否则入库与检索看到的不是同一份数据");
        assertNotSame(first, other, "不同 kbId 应相互隔离");
        assertTrue(factory.isInMemoryStore());
    }

    @Test
    public void storeShouldUseGivenDimensions() {
        VDBStoreBase small = factory.getStore("kb-small", 768);
        VDBStoreBase large = factory.getStore("kb-large", 1536);
        assertEquals(768, ((InMemoryStore) small).getDimensions());
        assertEquals(1536, ((InMemoryStore) large).getDimensions(), "不同知识库可绑定不同维度的向量模型");
    }

    @Test
    public void evictShouldDropCachedInstance() {
        VDBStoreBase before = factory.getStore("kb-evict", 768);
        factory.evictStore("kb-evict");
        VDBStoreBase after = factory.getStore("kb-evict", 768);

        assertNotSame(before, after, "evict 后应得到全新的空实例");
        assertTrue(((InMemoryStore) after).isEmpty());
    }

    @Test
    public void embeddingModelShouldBeCachedPerModelAndVersion() {
        EmbeddingModel first = factory.getEmbeddingModel(spec().build());
        EmbeddingModel again = factory.getEmbeddingModel(spec().build());
        assertSame(first, again, "同一模型、同一版本应复用客户端");

        EmbeddingModel rotated = factory.getEmbeddingModel(spec().apiKey("new-key").version("v2").build());
        assertNotSame(first, rotated, "版本变化（如更换密钥）后应重建客户端");

        EmbeddingModel other = factory.getEmbeddingModel(spec().modelId("model-b").build());
        assertNotSame(rotated, other, "不同模型应各自持有客户端");
    }

    @Test
    public void missingApiKeyShouldFailFast() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> factory.getEmbeddingModel(spec().apiKey(" ").build()));
        assertTrue(e.getMessage().contains("apiKey"));
    }

    @Test
    public void unsupportedProtocolShouldFailFast() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> factory.createEmbeddingModel(spec().protocol("DASHSCOPE").build()));
        assertTrue(e.getMessage().contains("DASHSCOPE"));
    }

    @Test
    public void storeTypeShouldBeCaseInsensitiveAndDefaultToInMemory() {
        properties.setStoreType(" in_memory ");
        assertTrue(factory.isInMemoryStore());

        properties.setStoreType(null);
        assertTrue(factory.isInMemoryStore());
    }
}
