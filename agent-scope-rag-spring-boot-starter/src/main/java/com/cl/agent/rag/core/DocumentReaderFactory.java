package com.cl.agent.rag.core;

import com.cl.agent.rag.properties.AgentRagProperties;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.reader.PDFReader;
import io.agentscope.core.rag.reader.Reader;
import io.agentscope.core.rag.reader.ReaderInput;
import io.agentscope.core.rag.reader.SplitStrategy;
import io.agentscope.core.rag.reader.TextReader;
import io.agentscope.core.rag.reader.TikaReader;
import io.agentscope.core.rag.reader.WordReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 多格式文档读取器解析与分块路由工厂类。
 * <p>基于配置项及上传的物理文件后缀名，动态路由至 AgentScope 官方最适配的
 * Document {@link Reader}，支持 PDF、Word (Docx/Doc)、Excel (Xlsx/Xls)、XML、TXT 等格式一站式解析。</p>
 */
@Slf4j
@Component
public class DocumentReaderFactory {

    /** RAG 属性配置项 */
    private final AgentRagProperties properties;

    /** 默认的重叠字符尺寸，防止段落边界信息断层 */
    private static final int DEFAULT_OVERLAP = 80;

    /**
     * 构造文档解析工厂。
     *
     * @param properties RAG 配置属性对象，非空
     */
    public DocumentReaderFactory(AgentRagProperties properties) {
        this.properties = properties;
    }

    /**
     * 根据物理文件类型后缀智能路由至对应的官方 Reader，完成文档的异步/同步内容抽取与段落切片。
     * <p>使用说明：由业务层在处理文档上传和向量化入库时调用；入参文件路径必须可读，且后缀合法。</p>
     *
     * @param filePath 物理文件在服务器的绝对存储路径，必填且非空
     * @param fileType 文件后缀扩展名类型（如 "pdf", "txt", "docx", "xlsx" 等），不区分大小写，必填
     * @return {@link Document} 列表；包含分块后的切片及元数据信息；若解析结果为空则返回空列表
     * @throws RuntimeException 当底层调用官方 Reader 发生 IO 异常或读取错误时抛出
     */
    public List<Document> parseFile(String filePath, String fileType) {
        String ext = fileType.trim().toLowerCase();
        int chunkSize = properties.getChunkSize();
        
        log.info("[RAG-Reader] 开始解析文档: path={}, type={}, chunkSize={}, overlap={}", 
                filePath, ext, chunkSize, DEFAULT_OVERLAP);
        
        Reader reader;
        switch (ext) {
            case "pdf":
                // 1. PDF 文件原生 Reader 装配
                reader = new PDFReader(chunkSize, SplitStrategy.PARAGRAPH, DEFAULT_OVERLAP);
                break;
                
            case "txt":
            case "md":
                // 2. 纯文本及 Markdown 文件原生 Reader 装配
                reader = new TextReader(chunkSize, SplitStrategy.PARAGRAPH, DEFAULT_OVERLAP);
                break;
                
            case "docx":
                // 3. Word 新版 Docx 文件原生 WordReader 装配
                reader = new WordReader(chunkSize, SplitStrategy.PARAGRAPH, DEFAULT_OVERLAP, false, false, null);
                break;
                
            case "xls":
            case "xlsx":
            case "doc":
            case "xml":
            case "pptx":
            case "ppt":
            default:
                // 4. 万能的 TikaReader 覆盖：支持 Excel 表格、旧版 Office 文档、XML 结构化抽取等所有格式
                log.info("[RAG-Reader] 动态装配 TikaReader 引擎解析文件类型: {}", ext);
                reader = new TikaReader(chunkSize, SplitStrategy.PARAGRAPH, DEFAULT_OVERLAP, null);
                break;
        }

        try {
            ReaderInput input;
            if ("txt".equals(ext) || "md".equals(ext)) {
                String content;
                Path path = Paths.get(filePath);
                try {
                    content = Files.readString(path, StandardCharsets.UTF_8);
                } catch (MalformedInputException e) {
                    log.warn("[RAG-Reader] UTF-8 解析失败，尝试使用 GBK 解析: path={}", filePath);
                    try {
                        content = Files.readString(path, Charset.forName("GBK"));
                    } catch (Exception ex) {
                        log.warn("[RAG-Reader] GBK 解析也失败，降级使用 ISO_8859_1 解析: path={}", filePath);
                        content = Files.readString(path, StandardCharsets.ISO_8859_1);
                    }
                }
                input = ReaderInput.fromString(content);
            } else {
                input = ReaderInput.fromPath(filePath);
            }
            // 调用官方 Reader 进行异步读取，并通过 block() 同步等待解析结果完成
            List<Document> docs = reader.read(input).block();
            int docCount = (docs != null) ? docs.size() : 0;
            log.info("[RAG-Reader] 文档解析并切片完成: path={}, 生成切片数={}", filePath, docCount);
            return docs != null ? docs : List.of();
        } catch (Exception e) {
            log.error("[RAG-Reader] 文档读取失败: path={}", filePath, e);
            throw new RuntimeException("文档读取解析失败: " + e.getMessage(), e);
        }
    }
}
