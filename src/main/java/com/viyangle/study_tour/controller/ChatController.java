package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.pojo.CreateChatGroupRequest;
import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.pojo.SendChatMessageRequest;
import com.viyangle.study_tour.service.ChatService;
import com.viyangle.study_tour.utils.SecurityContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/chat")
public class ChatController {

    @Autowired
    private ChatService chatService;

    @GetMapping("/sessions")
    public Result listSessions() {
        Long currentAccountId = SecurityContextUtil.currentAccountId();
        log.info("查询项目群聊列表: accountId={}", currentAccountId);
        return Result.success(chatService.listSessions(currentAccountId));
    }

    @GetMapping("/groups")
    public Result listGroups() {
        return listSessions();
    }

    @PostMapping("/groups")
    public Result createGroup(@RequestBody CreateChatGroupRequest request) {
        Long currentAccountId = SecurityContextUtil.currentAccountId();
        if (request == null) {
            throw new IllegalArgumentException("创建群组请求不能为空");
        }
        log.info("创建项目群组: projectId={}, accountId={}", request.getProjectId(), currentAccountId);
        return Result.success(chatService.createGroup(request.getProjectId(), currentAccountId));
    }

    @PostMapping("/groups/{sessionId}/join")
    public Result joinGroup(@PathVariable Long sessionId) {
        Long currentAccountId = SecurityContextUtil.currentAccountId();
        log.info("加入项目群组: sessionId={}, accountId={}", sessionId, currentAccountId);
        return Result.success(chatService.joinGroup(sessionId, currentAccountId));
    }

    @DeleteMapping("/groups/{sessionId}")
    public Result deleteGroup(@PathVariable Long sessionId) {
        Long currentAccountId = SecurityContextUtil.currentAccountId();
        log.info("删除项目群组: sessionId={}, accountId={}", sessionId, currentAccountId);
        chatService.deleteGroup(
                sessionId,
                currentAccountId,
                SecurityContextUtil.currentRole()
        );
        return Result.success();
    }

    @PostMapping("/messages")
    public Result sendMessage(@RequestBody SendChatMessageRequest request) {
        Long currentAccountId = SecurityContextUtil.currentAccountId();
        log.info("发送消息: sessionId={}, senderAccountId={}", request.getSessionId(), currentAccountId);
        Long msgId = chatService.sendMessage(request, currentAccountId);
        return Result.success(msgId);
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public Result listMessages(@PathVariable Long sessionId) {
        Long currentAccountId = SecurityContextUtil.currentAccountId();
        log.info("拉取消息: sessionId={}, accountId={}", sessionId, currentAccountId);
        return Result.success(chatService.listMessages(sessionId, currentAccountId));
    }

    @GetMapping("/sessions/{sessionId}/members")
    public Result listGroupMembers(@PathVariable Long sessionId) {
        Long currentAccountId = SecurityContextUtil.currentAccountId();
        log.info("查询群成员: sessionId={}, accountId={}", sessionId, currentAccountId);
        return Result.success(chatService.listGroupMembers(sessionId, currentAccountId));
    }
}
