package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建/获取聊天会话 请求DTO
 *
 * 说明：
 * - chat_sessions 表对 (project_id, user_account_id, leader_account_id) 做了唯一约束
 * - 所以这里的“创建”更准确说是：不存在则创建，存在则返回已有会话
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateChatSessionRequest {
    private Long projectId;
    private Long userAccountId;
    private Long leaderAccountId;
}



