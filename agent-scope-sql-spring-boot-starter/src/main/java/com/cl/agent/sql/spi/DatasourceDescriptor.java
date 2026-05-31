package com.cl.agent.sql.spi;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 数据源元数据描述符。
 *
 * <p>SPI 值对象，{@link DatasourceProvider#listAvailable(String)} 返回该对象列表，
 * LLM 通过 {@code list_datasources} 工具拿到 id/name/description 后再决定查询哪个库。</p>
 *
 * <p>本类故意不依赖 {@code agent-scope-model} 模块，保持 starter 与宿主实体定义的解耦：
 * 宿主的 {@code Datasource} 实体（含 jdbcUrl/username/passwordCipher）只在 service 层使用，
 * 跨 SPI 边界时只暴露这里的公开字段，避免泄露凭据。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatasourceDescriptor implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 数据源唯一 ID（宿主层为 UUID 字符串），LLM 调用工具时通过该 ID 选择目标库 */
    private String id;

    /** 展示名称，如「线上订单库」 */
    private String name;

    /** 用途描述，是 LLM 选择 datasourceId 的关键依据（建议宿主在注册时填详尽业务语义） */
    private String description;

    /** 数据库类型：mysql / postgres / ...，首期固定 "mysql" */
    private String dbType;
}
