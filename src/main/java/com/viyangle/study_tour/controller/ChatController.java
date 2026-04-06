package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.pojo.CreateChatSessionRequest;
import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.pojo.SendChatMessageRequest;
import com.viyangle.study_tour.service.ChatService;
import com.viyangle.study_tour.utils.SecurityContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
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

    @PostMapping("/sessions")
    public Result createOrGetSession(@RequestBody CreateChatSessionRequest request) {
        log.info("创建/获取会话: projectId={}, userAccountId={}, leaderAccountId={}",
            request.getProjectId(), request.getUserAccountId(), request.getLeaderAccountId());
        return Result.success(chatService.createOrGetSession(request));
    }

    @GetMapping("/sessions")
    public Result listSessions() {
        Long currentAccountId = SecurityContextUtil.currentAccountId();
        String currentRole = SecurityContextUtil.currentRole();
        log.info("查询会话列表: accountId={}, role={}", currentAccountId, currentRole);
        return Result.success(chatService.listSessions(currentAccountId, currentRole));
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
}
