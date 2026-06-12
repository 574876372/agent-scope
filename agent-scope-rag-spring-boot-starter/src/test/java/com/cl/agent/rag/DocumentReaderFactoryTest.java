package com.cl.agent.rag;

import com.cl.agent.rag.core.DocumentReaderFactory;
import com.cl.agent.rag.properties.AgentRagProperties;
import io.agentscope.core.rag.model.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * RAG 智能读取工厂 DocumentReaderFactory 单元测试类。
 * <p>基于 JUnit 5 运行。通过在临时目录下动态创建测试文本，验证 TextReader 的分片和内容提取逻辑。</p>
 */
public class DocumentReaderFactoryTest {

    /** 临时测试文本文件绝对路径 */
    private Path tempFilePath;

    /** 智能读取工厂实例 */
    private DocumentReaderFactory readerFactory;

    /**
     * 测试前置环境初始化。
     * <p>在 OS 临时目录下构建一个包含多行长句子的测试文件，配置分片尺寸为 100 字符。</p>
     *
     * @throws IOException 当创建临时文件失败时抛出
     */
    @BeforeEach
    public void setUp() throws IOException {
        // 创建临时测试文件
        tempFilePath = Files.createTempFile("rag_test_doc", ".txt");
        String testContent = "这是第一行测试文本。\n" +
                "这是第二行非常长非常长非常长的测试文本，用以触发切块算法的分片拆分边界规则。\n" +
                "这是第三行用于验证的参考字句。";
        Files.writeString(tempFilePath, testContent);

        // 初始化属性配置与工厂实例
        AgentRagProperties properties = new AgentRagProperties();
        properties.setChunkSize(100); // 较小分片大小，触发分割
        readerFactory = new DocumentReaderFactory(properties);
    }

    /**
     * 测试后置清理。
     * <p>清除临时磁盘文件，避免资源泄漏。</p>
     *
     * @throws IOException 当删除失败时抛出
     */
    @AfterEach
    public void tearDown() throws IOException {
        Files.deleteIfExists(tempFilePath);
    }

    /**
     * 测试 TXT/纯文本文件解析，验证切片生成与内容提取的精确性。
     */
    @Test
    public void testParseTxtFile() {
        assertNotNull(readerFactory, "读取工厂不应为空");
        
        // 执行解析
        List<Document> docs = readerFactory.parseFile(tempFilePath.toString(), "txt");
        
        // 断言验证
        assertNotNull(docs, "解析出来的文档切片列表不应为空");
        assertFalse(docs.isEmpty(), "解析结果至少应包含一个有效切片");
        
        // 验证切片内容中包含特定文本段
        String firstChunkText = docs.get(0).getMetadata().getContentText();
        assertNotNull(firstChunkText, "切片文本内容不能为空");
        assertTrue(firstChunkText.contains("测试文本"), "切片文本应召回正确的测试用字句");
    }
}
