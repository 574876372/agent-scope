package com.cl.agent.controller;

import com.cl.agent.biz.IToolBiz;
import com.cl.agent.dto.ToolConfigResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 工具管理 API 控制器
 */
@RestController
@RequestMapping("/api/tools")
@CrossOrigin(origins = "*")
public class ToolController {

    @Autowired
    private IToolBiz toolBiz;

    /**
     * 获取所有可用的工具列表（供前端"工具市场"展示）
     *
     * @return 工具配置列表
     */
    @GetMapping
    public ResponseEntity<List<ToolConfigResponse>> listTools() {
        return ResponseEntity.ok(toolBiz.listAvailableTools());
    }
}
