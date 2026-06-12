package com.cl.agent.controller;

import com.cl.agent.biz.IDatasourceBiz;
import com.cl.agent.dto.sql.DatasourceRequest;
import com.cl.agent.dto.sql.DatasourceResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 外部业务数据源管理 REST 控制器层。
 * <p>提供数据源的完整生命周期管理（新增、查询、更新、删除）以及连接测试能力。
 * 所有接口均使用明确的固定路径，参数通过 {@code @RequestParam} 查询参数传递，
 * 禁止使用 {@code {pathVariable}} 动态路径变量方式传递业务 ID。
 * 用户身份通过请求头 {@code X-User-Id} 经 {@code AuthInterceptor} 注入 {@code UserContext}，
 * 本 Controller 不显式处理。</p>
 *
 * <h2>路由一览</h2>
 * <ul>
 *   <li>{@code GET    /api/datasources/list}          — 列出当前用户全部启用数据源</li>
 *   <li>{@code POST   /api/datasources/create}        — 新增数据源</li>
 *   <li>{@code PUT    /api/datasources/update}        — 更新数据源，?id=</li>
 *   <li>{@code DELETE /api/datasources/delete}        — 删除数据源，?id=</li>
 *   <li>{@code POST   /api/datasources/test/new}      — 测试新连接（请求体含全部连接信息）</li>
 *   <li>{@code POST   /api/datasources/test/existing} — 测试已有数据源，?id=</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/datasources")
@CrossOrigin(origins = "*")
public class DatasourceController {

    /** 数据源业务编排层 */
    @Autowired
    private IDatasourceBiz datasourceBiz;

    /**
     * 列出当前登录用户的全部启用数据源。
     * <p>使用说明：由前端数据源管理列表页初始化时调用，userId 从 UserContext 自动读取，无需传参。</p>
     *
     * @return {@link ResponseEntity} 包含数据源列表（按更新时间倒序），HTTP 状态码 200；无数据时返回空列表
     */
    @GetMapping("/list")
    public ResponseEntity<List<DatasourceResponse>> list() {
        return ResponseEntity.ok(datasourceBiz.listMine());
    }

    /**
     * 新增一条外部数据源配置，密码将加密存储。
     * <p>使用说明：由前端"新建数据源"表单提交，{@code passwordPlain} 必填；密码经 AES 加密后落库，响应中不回显。</p>
     *
     * @param request 创建请求体，{@code jdbcUrl}、{@code username}、{@code passwordPlain} 必填
     * @return {@link ResponseEntity} 包含创建后的数据源详情（不含密码），HTTP 状态码 200
     */
    @PostMapping("/create")
    public ResponseEntity<DatasourceResponse> create(@RequestBody DatasourceRequest request) {
        return ResponseEntity.ok(datasourceBiz.create(request));
    }

    /**
     * 更新已有数据源的连接配置信息。
     * <p>使用说明：由前端"编辑数据源"表单提交，?id= 传入目标数据源 ID；
     * {@code passwordPlain} 留空表示不修改密码，仅更新其他字段。</p>
     *
     * @param id      数据源唯一 ID，非空，通过查询参数 {@code ?id=} 传入
     * @param request 更新请求体；{@code passwordPlain} 留空表示不修改密码
     * @return {@link ResponseEntity} 包含更新后的数据源详情（不含密码），HTTP 状态码 200
     */
    @PutMapping("/update")
    public ResponseEntity<DatasourceResponse> update(@RequestParam("id") String id,
                                                     @RequestBody DatasourceRequest request) {
        return ResponseEntity.ok(datasourceBiz.update(id, request));
    }

    /**
     * 软删除指定数据源，并自动清理本地连接池缓存。
     * <p>使用说明：由前端"删除数据源"确认弹窗提交，?id= 传入目标数据源 ID；操作不可逆。</p>
     *
     * @param id 数据源唯一 ID，非空，通过查询参数 {@code ?id=} 传入
     * @return {@link ResponseEntity} 无返回值，HTTP 状态码 204
     */
    @DeleteMapping("/delete")
    public ResponseEntity<Void> delete(@RequestParam("id") String id) {
        datasourceBiz.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 测试一段未保存的新连接配置是否可用。
     * <p>使用说明：在"新建数据源"表单中，用户填写完参数后点击"测试连接"时调用；
     * 请求体须包含完整的 JDBC 连接信息，服务端不会持久化任何数据。</p>
     *
     * @param request 连接参数请求体，{@code jdbcUrl}、{@code username}、{@code passwordPlain} 必填
     * @return {@link ResponseEntity} 包含 {@code {"success": boolean}} 结果，HTTP 状态码 200
     */
    @PostMapping("/test/new")
    public ResponseEntity<Map<String, Object>> testNew(@RequestBody DatasourceRequest request) {
        boolean ok = datasourceBiz.testConnection(request);
        return ResponseEntity.ok(Map.of("success", ok));
    }

    /**
     * 测试已有数据源的连接可用性，自动回源加密密码，无需用户重新输入。
     * <p>使用说明：前端"测试连接"按钮可在不重输密码的情况下复测已有数据源；
     * ?id= 传入数据源 ID，若同时传入 {@code passwordPlain}，将以请求体中的明文为准覆盖。</p>
     *
     * @param id      数据源唯一 ID，非空，通过查询参数 {@code ?id=} 传入
     * @param request 可选连接参数覆盖请求体，未传时全部回源数据库中的加密配置
     * @return {@link ResponseEntity} 包含 {@code {"success": boolean}} 结果，HTTP 状态码 200
     */
    @PostMapping("/test/existing")
    public ResponseEntity<Map<String, Object>> testExisting(@RequestParam("id") String id,
                                                            @RequestBody(required = false) DatasourceRequest request) {
        DatasourceRequest req = request == null ? new DatasourceRequest() : request;
        req.setId(id);
        boolean ok = datasourceBiz.testConnection(req);
        return ResponseEntity.ok(Map.of("success", ok));
    }
}
