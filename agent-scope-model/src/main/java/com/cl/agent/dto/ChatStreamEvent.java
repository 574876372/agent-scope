package com.cl.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 聊天流式 SSE 事件 DTO，由 biz 层产出、web 层映射为 {@code ServerSentEvent}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatStreamEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** SSE 事件名：reasoning / tool_result / message / error；为空表示控制帧 */
    private String event;

    /** 事件数据载荷 */
    private String data;
}
