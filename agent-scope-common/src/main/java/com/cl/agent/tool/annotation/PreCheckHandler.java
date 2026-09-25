package com.cl.agent.tool.annotation;

import java.util.Map;

/**
 * 工具预检处理器接口。
 * <p>使用说明：如果执行审批拦截的工具 Bean 实现了此接口，在生成审批 Token 前会先调用 {@link #preCheck}，
 * 其返回的元数据（如 SQL 估算行数、警告提示等）会随 {@code PENDING_APPROVAL} 状态一同返回给前端渲染展示。</p>
 */
public interface PreCheckHandler {

    /**
     * 执行审批前的参数与运行预检，生成附带的说明和元数据。
     * <p>使用说明：在拦截器拦截到方法需要审批时，反射调用真实 Bean 之前，自动触发调用此方法。</p>
     *
     * @param parameters 大模型传入的工具调用参数 Map，非空
     * @return 预检产出的元数据 Map（如包含 warnings、estimatedRows 等），不能为 null，无数据时返回空 Map
     */
    Map<String, Object> preCheck(Map<String, Object> parameters);
}
