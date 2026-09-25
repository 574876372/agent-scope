package com.cl.agent.biz.rag;

import com.cl.agent.exception.BizException;
import com.cl.agent.rag.properties.AgentRagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link KnowledgeFileStorage} 单元测试：相对路径落库、按日期分层、兼容早期绝对路径、拒绝越出根目录。
 */
class KnowledgeFileStorageTest {

    @TempDir
    Path root;

    private KnowledgeFileStorage storage;

    @BeforeEach
    void setUp() {
        AgentRagProperties props = new AgentRagProperties();
        props.setUploadDir(root.toString());
        storage = new KnowledgeFileStorage();
        ReflectionTestUtils.setField(storage, "ragProperties", props);
    }

    private static ByteArrayInputStream content(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void storeShouldReturnSlashSeparatedRelativePathWithDateDirs() throws Exception {
        String relative = storage.store("kb-1", "doc-1", "md", content("hello"));

        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        assertEquals("kb-1/" + today + "/doc-1.md", relative, "落库路径应为相对路径且统一使用 / 分隔");
        assertFalse(relative.contains("\\"));

        Path actual = storage.resolve(relative);
        assertTrue(actual.startsWith(root), "实际文件应位于配置的根目录下");
        assertEquals("hello", Files.readString(actual));
    }

    @Test
    void changingRootShouldResolveRelativePathAgainstNewRoot(@TempDir Path newRoot) {
        String relative = storage.store("kb-1", "doc-2", "txt", content("x"));

        AgentRagProperties moved = new AgentRagProperties();
        moved.setUploadDir(newRoot.toString());
        ReflectionTestUtils.setField(storage, "ragProperties", moved);

        assertEquals(newRoot.resolve(relative).normalize(), storage.resolve(relative), "修改根目录后应按新根目录解析");
    }

    @Test
    void legacyAbsolutePathShouldBeUsedAsIs() {
        Path legacy = root.resolve("legacy").resolve("old.md").toAbsolutePath();
        assertEquals(legacy.normalize(), storage.resolve(legacy.toString()), "早期保存的绝对路径应原样使用");
    }

    @Test
    void relativePathEscapingRootShouldBeRejected() {
        assertThrows(BizException.class, () -> storage.resolve("../outside/secret.txt"));
    }

    @Test
    void deleteQuietlyShouldRemoveFileAndIgnoreMissing() {
        String relative = storage.store("kb-1", "doc-3", "txt", content("x"));
        Path actual = storage.resolve(relative);

        storage.deleteQuietly(relative);
        assertFalse(Files.exists(actual));

        assertDoesNotThrow(() -> storage.deleteQuietly(relative), "文件已不存在时不应抛异常");
        assertDoesNotThrow(() -> storage.deleteQuietly("../outside.txt"), "非法路径只记录日志");
    }
}
