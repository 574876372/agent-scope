package com.cl.agent.biz.rag;

import com.cl.agent.exception.BizException;
import com.cl.agent.rag.properties.AgentRagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 知识库上传文档的本地文件存储。
 * <p>目录结构：{@code {agent.rag.upload-dir}/{知识库ID}/yyyy/MM/dd/{文档ID}.{扩展名}}。</p>
 * <p>数据库 {@code t_knowledge_document.file_path} 只保存相对于根目录的路径（统一用 {@code /} 分隔），
 * 读写时再与当前配置的根目录拼接，因此同一份数据在 Windows 与 Linux 之间迁移、或修改根目录后都能继续使用。
 * 早期版本保存的是绝对路径，{@link #resolve(String)} 会原样使用，无需迁移。</p>
 */
@Slf4j
@Component
public class KnowledgeFileStorage {

    /** 未配置 agent.rag.upload-dir 时的默认根目录 */
    private static final String DEFAULT_UPLOAD_DIR = "./data/uploads";

    /** 按日期分层，避免单个目录下文件过多 */
    private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /** RAG 未启用时为 null，使用默认根目录 */
    @Autowired(required = false)
    private AgentRagProperties ragProperties;

    /**
     * 保存上传文件。
     *
     * @param kbId   知识库 ID
     * @param fileId 文档 ID，作为文件名
     * @param ext    已清洗的扩展名（仅字母数字）
     * @param input  文件内容
     * @return 相对于根目录的存储路径，用 {@code /} 分隔，可直接落库
     * @throws BizException 写入磁盘失败时抛出
     */
    public String store(String kbId, String fileId, String ext, InputStream input) {
        String relativePath = String.join("/", kbId, LocalDate.now().format(DATE_DIR), fileId + "." + ext);
        Path target = resolve(relativePath);
        try {
            Files.createDirectories(target.getParent());
            // 直接写输入流，避免 MultipartFile.transferTo 把相对路径拼到 Servlet 容器的临时目录下
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("[Doc-Storage] 文件已保存: path={}", target);
            return relativePath;
        } catch (IOException e) {
            log.error("[Doc-Storage] 文件写入磁盘失败: path={}", target, e);
            throw new BizException(500, "文件写入本地失败: " + e.getMessage());
        }
    }

    /**
     * 把数据库中保存的路径解析为当前系统上的实际文件路径。
     *
     * @param storedPath 数据库中的 file_path：新数据为相对路径，早期数据为绝对路径
     * @return 实际文件路径
     * @throws BizException 相对路径试图越出根目录时抛出
     */
    public Path resolve(String storedPath) {
        Path path = Paths.get(storedPath);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        Path root = rootDir();
        Path resolved = root.resolve(storedPath).normalize();
        if (!resolved.startsWith(root)) {
            throw new BizException(400, "非法的文件路径: " + storedPath);
        }
        return resolved;
    }

    /**
     * 删除文件；文件不存在或删除失败时只记录日志，不影响后续的数据清理。
     *
     * @param storedPath 数据库中的 file_path
     */
    public void deleteQuietly(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            return;
        }
        try {
            Path path = resolve(storedPath);
            if (Files.deleteIfExists(path)) {
                log.info("[Doc-Storage] 文件已删除: path={}", path);
            } else {
                log.warn("[Doc-Storage] 文件不存在，跳过删除: path={}", path);
            }
        } catch (Exception e) {
            log.warn("[Doc-Storage] 删除文件失败: storedPath={}, reason={}", storedPath, e.getMessage());
        }
    }

    /** 当前配置的根目录（绝对、规范化） */
    private Path rootDir() {
        String dir = ragProperties != null ? ragProperties.getUploadDir() : null;
        String effective = (dir == null || dir.isBlank()) ? DEFAULT_UPLOAD_DIR : dir.trim();
        return Paths.get(effective).toAbsolutePath().normalize();
    }
}
