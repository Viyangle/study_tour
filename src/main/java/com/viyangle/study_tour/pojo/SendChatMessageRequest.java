package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发送聊天消息 请求DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SendChatMessageRequest {
    private Long sessionId;
    private Long senderAccountId;
    private String content;
    /**
     * 消息类型：TEXT / IMAGE / SYSTEM
     * 为空时默认 TEXT
     */
    private String msgType;
}



