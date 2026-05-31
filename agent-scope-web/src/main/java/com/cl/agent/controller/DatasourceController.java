package com.cl.agent.controller;

import com.cl.agent.biz.IDatasourceBiz;
import com.cl.agent.dto.sql.DatasourceRequest;
import com.cl.agent.dto.sql.DatasourceResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 外部业务数据源管理 REST API。
 *
 * <h2>路由</h2>
 * <ul>
 *   <li>{@code GET    /api/datasources}            — 列出当前用户全部启用数据源</li>
 *   <li>{@code POST   /api/datasources}            — 新增</li>
 *   <li>{@code PUT    /api/datasources/{id}}       — 更新</li>
 *   <li>{@code DELETE /api/datasources/{id}}       — 删除</li>
 *   <li>{@code POST   /api/datasources/test}       — 测试新连接（请求体含全部连接信息）</li>
 *   <li>{@code POST   /api/datasources/{id}/test}  — 测试已有数据源（自动回源 cipher）</li>
 * </ul>
 *
 * <p>用户身份通过请求头 {@code X-User-Id} 经 {@code AuthInterceptor} 注入 {@code UserContext}，
 * 本 Controller 不显式处理。</p>
 */
@RestController
@RequestMapping("/api/datasources")
@CrossOrigin(origins = "*")
public class DatasourceController {

    /** 数据源业务 */
    @Autowired
    private IDatasourceBiz datasourceBiz;

    /**
     * 列出当前用户的全部启用数据源。
     *
     * @return 数据源列表（按更新时间倒序）
     */
    @GetMapping
    public ResponseEntity<List<DatasourceResponse>> list() {
        return ResponseEntity.ok(datasourceBiz.listMine());
    }

    /**
     * 新增数据源。
     *
     * @param request 创建请求体；{@code passwordPlain} 必填
     * @return 创建后的实体（不含密码）
     */
    @PostMapping
    public ResponseEntity<DatasourceResponse> create(@RequestBody DatasourceRequest request) {
        return ResponseEntity.ok(datasourceBiz.create(request));
    }

    /**
     * 更新数据源。
     *
     * @param id      数据源 ID（路径变量）
     * @param request 更新请求体；{@code passwordPlain} 留空表示不修改密码
     * @return 更新后的实体（不含密码）
     */
    @PutMapping("/{id}")
    public ResponseEntity<DatasourceResponse> update(@PathVariable String id,
                                                     @RequestBody DatasourceRequest request) {
        return ResponseEntity.ok(datasourceBiz.update(id, request));
    }

    /**
     * 删除数据源（软删 + 清缓存）。
     *
     * @param id 数据源 ID
     * @return 204 No Content
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        datasourceBiz.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 测试一段未保存的连接配置是否可用。
     *
     * @param request 连接参数；要求 {@code jdbcUrl/username/passwordPlain} 必填
     * @return {@code {"success": boolean}}
     */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testNew(@RequestBody DatasourceRequest request) {
        boolean ok = datasourceBiz.testConnection(request);
        return ResponseEntity.ok(Map.of("success", ok));
    }

    /**
     * 测试已有数据源连接（自动回源 cipher，无需重新输入密码）。
     *
     * <p>使用说明：前端"测试连接"按钮可在不重输密码的情况下复测；
     * 若同时传入 {@code passwordPlain}，将以该明文为准。</p>
     *
     * @param id      数据源 ID
     * @param request 可选连接参数覆盖，未传时全部回源 DB
     * @return {@code {"success": boolean}}
     */
    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, Object>> testExisting(@PathVariable String id,
                                                            @RequestBody(required = false) DatasourceRequest request) {
        DatasourceRequest req = request == null ? new DatasourceRequest() : request;
        req.setId(id);
        boolean ok = datasourceBiz.testConnection(req);
        return ResponseEntity.ok(Map.of("success", ok));
    }
}
