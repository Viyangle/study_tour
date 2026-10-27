package com.viyangle.study_tour.pojo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

    @NotNull(message = "群组ID不能为空")
    private Long sessionId;

    @NotBlank(message = "消息内容不能为空")
    @Size(max = 1000, message = "消息内容不能超过1000个字符")
    private String content;

    /**
     * 消息类型：TEXT / IMAGE / SYSTEM
     * 为空时默认 TEXT
     */
    @Pattern(regexp = "(?i)^(TEXT|IMAGE|SYSTEM)$", message = "消息类型仅支持TEXT/IMAGE/SYSTEM")
    private String msgType;
}



