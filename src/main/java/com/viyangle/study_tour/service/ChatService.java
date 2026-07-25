package com.viyangle.study_tour.service;

import com.viyangle.study_tour.pojo.ChatMessage;
import com.viyangle.study_tour.pojo.ChatGroupMember;
import com.viyangle.study_tour.pojo.ChatSession;
import com.viyangle.study_tour.pojo.SendChatMessageRequest;

import java.util.List;

public interface ChatService {
    /** 项目发布者显式创建或恢复项目群。 */
    ChatSession createGroup(Long projectId, Long currentAccountId);

    /** 符合项目关系的账号显式加入群组，重复加入按成功处理。 */
    ChatSession joinGroup(Long sessionId, Long currentAccountId);

    /** 普通参团成员退出群聊，不影响其项目成员关系。 */
    void leaveGroup(Long sessionId, Long currentAccountId);

    /** 项目发布者或管理员删除群组；底层使用软删除保留历史消息。 */
    void deleteGroup(Long sessionId, Long currentAccountId, String currentRole);

    /**
     * 项目发布后由项目服务自动创建唯一群聊，并随项目关系同步成员。
     */
    ChatSession createProjectGroup(Long projectId, Long ownerAccountId, Long leaderAccountId);

    /**
     * 项目结束后停用群聊。
     */
    void deleteProjectGroup(Long projectId);

    /** 项目成员加入项目后自动同步群成员关系。 */
    void joinProjectGroup(Long projectId, Long accountId);

    /**
     * 查询账号作为项目成员或领队参与的群聊。
     */
    List<ChatSession> listSessions(Long accountId);

    /**
     * 发送消息
     * @return 新消息id
     */
    Long sendMessage(SendChatMessageRequest request, Long currentAccountId);

    /**
     * 拉取会话消息列表
     */
    List<ChatMessage> listMessages(Long sessionId, Long currentAccountId);

    /** 查询群成员及每个账号代表的实际参团人数。 */
    List<ChatGroupMember> listGroupMembers(Long sessionId, Long currentAccountId);
}
