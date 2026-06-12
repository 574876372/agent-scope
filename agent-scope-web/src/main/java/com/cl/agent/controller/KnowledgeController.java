package com.cl.agent.controller;

import com.cl.agent.biz.IKnowledgeBiz;
import com.cl.agent.dto.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库及文档解析管理 REST 控制器层。
 * <p>遵循 RESTful API 设计规范。提供对知识库主体的 CRUD 操作路由、
 * 物理文档的多段 Multipart 表单异步接收上传、以及检索演练场检索测试等端点接入。
 * 所有接口均使用明确的固定路径，参数通过 {@code @RequestParam} 查询参数传递，
 * 禁止使用 {@code {pathVariable}} 动态路径变量方式传递业务 ID。
 * 遵循严格的层级边界规范，仅调用 {@link IKnowledgeBiz} 业务编排层。</p>
 *
 * <h2>路由一览</h2>
 * <ul>
 *   <li>{@code POST   /api/knowledge-base/create}            — 创建知识库</li>
 *   <li>{@code GET    /api/knowledge-base/list}              — 列出当前用户的知识库</li>
 *   <li>{@code GET    /api/knowledge-base/detail}            — 获取单个知识库详情，?id=</li>
 *   <li>{@code DELETE /api/knowledge-base/delete}            — 级联删除知识库，?id=</li>
 *   <li>{@code POST   /api/knowledge-base/document/upload}   — 上传文档，?kbId=</li>
 *   <li>{@code GET    /api/knowledge-base/document/list}     — 列出文档，?kbId=</li>
 *   <li>{@code DELETE /api/knowledge-base/document/delete}   — 删除文档，?docId=</li>
 *   <li>{@code GET    /api/knowledge-base/search}            — 检索演练场，?kbId=&query=&limit=</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/knowledge-base")
@CrossOrigin(origins = "*")
public class KnowledgeController {

    @Autowired
    private IKnowledgeBiz knowledgeBiz;

    /**
     * 创建并持久化一个全新的私有知识库。
     * <p>使用说明：由前端"创建知识库"表单提交；当前登录用户 ID 会在业务层自动从上下文读取隔离。</p>
     *
     * @param request 创建知识库参数体，{@code name} 必填，非空
     * @return {@link ResponseEntity} 包含创建成功的知识库响应对象，HTTP 状态码 200
     */
    @PostMapping("/create")
    public ResponseEntity<KbResponse> createKb(@RequestBody CreateKbRequest request) {
        return ResponseEntity.ok(knowledgeBiz.createKnowledgeBase(request));
    }

    /**
     * 列出当前登录用户下创建的所有活跃知识库列表。
     *
     * @return {@link ResponseEntity} 包含知识库响应列表，HTTP 状态码 200；未创建时返回空列表
     */
    @GetMapping("/list")
    public ResponseEntity<List<KbResponse>> listKbs() {
        return ResponseEntity.ok(knowledgeBiz.listKnowledgeBases());
    }

    /**
     * 获取指定 ID 知识库的详细配置元数据信息。
     * <p>使用说明：由前端知识库详情页或编辑页调用，?id= 传入知识库唯一 ID。</p>
     *
     * @param id 知识库的唯一 ID，非空，通过查询参数 {@code ?id=} 传入
     * @return {@link ResponseEntity} 包含知识库详情响应，HTTP 状态码 200
     * @throws com.cl.agent.exception.BizException 当知识库不存在时，HTTP 404
     */
    @GetMapping("/detail")
    public ResponseEntity<KbResponse> getKb(@RequestParam("id") String id) {
        return ResponseEntity.ok(knowledgeBiz.getKnowledgeBase(id));
    }

    /**
     * 级联删除指定 ID 的知识库，清理其关联的所有文件、切片与向量库分区。
     * <p>使用说明：由前端"删除知识库"确认弹窗提交，?id= 传入目标知识库 ID；
     * 此操作具有不可逆的级联副作用，会同步删除所有下属文档与向量索引。</p>
     *
     * @param id 待删除知识库的 ID，非空，通过查询参数 {@code ?id=} 传入
     * @return {@link ResponseEntity} 无返回值，HTTP 状态码 204
     */
    @DeleteMapping("/delete")
    public ResponseEntity<Void> deleteKb(@RequestParam("id") String id) {
        knowledgeBiz.deleteKnowledgeBase(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 物理上传一个文档文件并将其绑定至指定的知识库，自动提交后台进行异步切片向量化。
     * <p>使用说明：支持上传 PDF, DOCX, XLSX, TXT, XML, MD 等全格式文件。以 Multipart 表单接收文件二进制数据；
     * 接口立即返回 uploading 状态，异步向量化在后台执行，前端应轮询文档状态接口。</p>
     *
     * @param kbId 目标绑定的知识库唯一 ID，非空，通过查询参数 {@code ?kbId=} 传入
     * @param file 物理文件二进制表单体，参数名 {@code file}，必填，非空
     * @return {@link ResponseEntity} 包含文档元数据明细与 uploading 初始状态，HTTP 状态码 200
     */
    @PostMapping(value = "/document/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadDocResponse> uploadDoc(
            @RequestParam("kbId") String kbId,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(knowledgeBiz.uploadAndIndexDocument(kbId, file));
    }

    /**
     * 列出指定知识库下绑定的所有上传文档记录及解析状态。
     * <p>使用说明：主要用于文档管理器列表展示，反映每一个文件的字数和状态。</p>
     *
     * @param kbId 知识库 ID，非空，通过查询参数 {@code ?kbId=} 传入
     * @return {@link ResponseEntity} 包含上传文档列表响应，HTTP 状态码 200；为空时返回空列表
     */
    @GetMapping("/document/list")
    public ResponseEntity<List<UploadDocResponse>> listDocs(@RequestParam("kbId") String kbId) {
        return ResponseEntity.ok(knowledgeBiz.listDocuments(kbId));
    }

    /**
     * 逻辑删除指定的文档数据，级联清理 MySQL 切片与向量库索引。
     * <p>使用说明：由文档管理列表的"删除"操作触发，?docId= 传入目标文档 ID；
     * 会同步清理对应向量数据库中的所有切片索引，操作不可逆。</p>
     *
     * @param docId 待删除文档的唯一 ID，非空，通过查询参数 {@code ?docId=} 传入
     * @return {@link ResponseEntity} 无返回值，HTTP 状态码 204
     */
    @DeleteMapping("/document/delete")
    public ResponseEntity<Void> deleteDoc(@RequestParam("docId") String docId) {
        knowledgeBiz.deleteDocument(docId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 知识库检索演练场 (Playground) 的专属测试语义检索端点。
     * <p>使用说明：输入查询词，返回相关的文本片段和得分卡片，用以调优向量分块与模型相关度匹配参数；
     * 该接口会实时触发 Embedding 计算，不建议高频调用。</p>
     *
     * @param kbId  目标知识库唯一 ID，非空，通过查询参数 {@code ?kbId=} 传入
     * @param query 用户输入的自然语言测试查询词，非空，通过查询参数 {@code ?query=} 传入
     * @param limit 召回切片最大数量，非必填，为空时使用系统默认值（3）
     * @return {@link ResponseEntity} 包含召回文本片段与相似度得分列表，HTTP 状态码 200；未命中时返回空列表
     */
    @GetMapping("/search")
    public ResponseEntity<List<SearchChunkResponse>> search(
            @RequestParam("kbId") String kbId,
            @RequestParam("query") String query,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return ResponseEntity.ok(knowledgeBiz.searchKnowledge(kbId, query, limit));
    }
}
