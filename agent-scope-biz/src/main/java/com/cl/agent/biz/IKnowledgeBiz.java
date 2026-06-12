package com.cl.agent.biz;

import com.cl.agent.dto.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

/**
 * 知识库业务编排层 (Biz) 核心接口。
 * <p>处理前端提交的复杂数据控制流，包括文件上传物理落盘、异步投递向量化流水线、
 * 检索演练场的测试召回以及 Agent 与知识库关系关联绑定等。</p>
 */
public interface IKnowledgeBiz {

    /**
     * 创建并持久化一个全新的私有知识库。
     * <p>使用说明：由知识库控制器在收到创建请求时调用；当前操作用户 ID 会自动从 UserContext 上下文中安全解析获取。</p>
     *
     * @param request 创建知识库参数请求体，{@code name} 必填，非空
     * @return {@link KbResponse} 创建成功后的知识库详细元数据信息对象
     */
    KbResponse createKnowledgeBase(CreateKbRequest request);

    /**
     * 列出当前登录用户创建并拥有的所有活跃知识库列表。
     *
     * @return {@link KbResponse} 列表；未查询到时返回空列表
     */
    List<KbResponse> listKnowledgeBases();

    /**
     * 获取指定 ID 的知识库详细元数据。
     *
     * @param id 知识库唯一标识 ID，非空
     * @return {@link KbResponse} 知识库详情响应对象
     * @throws com.cl.agent.exception.BizException 当对应的知识库不存在时抛出 404 错误
     */
    KbResponse getKnowledgeBase(String id);

    /**
     * 级联删除指定 ID 的知识库。
     * <p>使用说明：除了清理 MySQL 的知识库主表外，还会级联清除文档元数据、文本切片、
     * 多对多绑定关系以及向量库物理索引，具有彻底清除的副作用。</p>
     *
     * @param id 待删除知识库的 ID，非空
     * @return 无
     */
    void deleteKnowledgeBase(String id);

    /**
     * 上传并异步向量化单个文档。
     * <p>使用说明：上传的文件将被安全暂存在服务器本地 workspace 的数据存储区中；
     * 随后自动在数据库中生成一条处于 {@code uploading} 状态的文档监控记录，
     * 并将具体的读取分片与向量化计算解析任务异步投递至后台线程池进行解耦消费，立即响应前端。</p>
     *
     * @param kbId 目标绑定的知识库唯一 ID，非空
     * @param file 前端提交的 Multipart 物理文件，必填且非空
     * @return {@link UploadDocResponse} 包含文档初步登记元数据与上传中状态的响应对象
     * @throws com.cl.agent.exception.BizException 当文件为空、格式不支持或所属知识库不存在时抛出
     */
    UploadDocResponse uploadAndIndexDocument(String kbId, MultipartFile file);

    /**
     * 获取指定知识库下关联的所有文档解析列表。
     * <p>使用说明：主要用于前端在“文档管理”列表页面展示，跟踪每一个文件的解析入库状态与字数信息。</p>
     *
     * @param kbId 知识库 ID，非空
     * @return {@link UploadDocResponse} 列表；为空时返回空列表
     */
    List<UploadDocResponse> listDocuments(String kbId);

    /**
     * 逻辑删除知识库下的单个文档及级联文本切片。
     * <p>使用说明：会同步清理向量数据库中该文档关联的所有向量索引。</p>
     *
     * @param docId 待删除文档的唯一 ID，非空
     * @return 无
     */
    void deleteDocument(String docId);

    /**
     * 知识库检索演练场 (Playground) 专属语义召回检索测试。
     * <p>使用说明：模拟 Agent 的检索流程。在指定的知识库中，根据用户输入的自然语言提问进行 Similarity Search，
     * 召回 Top-K 最相似段落，返回用于高亮渲染的数据列表。</p>
     *
     * @param kbId  目标知识库唯一 ID，非空
     * @param query 用户输入的自然语言测试查询词，非空
     * @param limit 召回切片最大数量上限，null 时使用系统默认配置
     * @return {@link SearchChunkResponse} 召回的文本片段与相似度得分列表；未检索到时返回空列表
     */
    List<SearchChunkResponse> searchKnowledge(String kbId, String query, Integer limit);
}
