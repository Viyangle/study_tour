package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.pojo.CreateChatSessionRequest;
import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.pojo.SendChatMessageRequest;
import com.viyangle.study_tour.service.ChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@Slf4j
@RestController
@RequestMapping("/chat")
public class ChatController {

    @Autowired
    private ChatService chatService;

    /**
     * 创建或获取会话
     * POST /chat/sessions
     */
    @PostMapping("/sessions")
    public Result createOrGetSession(@RequestBody CreateChatSessionRequest request) {
        log.info("创建/获取会话: projectId={}, userAccountId={}, leaderAccountId={}",
            request.getProjectId(), request.getUserAccountId(), request.getLeaderAccountId());
        return Result.success(chatService.createOrGetSession(request));
    }

    /**
     * 查询会话列表
     * GET /chat/sessions?accountId=1&role=USER
     */
    @GetMapping("/sessions")
    public Result listSessions(@RequestParam Long accountId, @RequestParam(defaultValue = "USER") String role) {
        log.info("查询会话列表: accountId={}, role={}", accountId, role);
        return Result.success(chatService.listSessions(accountId, role));
    }

    /**
     * 发送消息
     * POST /chat/messages
     */
    @PostMapping("/messages")
    public Result sendMessage(@RequestBody SendChatMessageRequest request) {
        log.info("发送消息: sessionId={}, senderAccountId={}", request.getSessionId(), request.getSenderAccountId());
        Long msgId = chatService.sendMessage(request);
        return Result.success(msgId);
    }

    /**
     * 拉取某个会话的消息列表
     * GET /chat/sessions/{sessionId}/messages
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public Result listMessages(@PathVariable Long sessionId) {
        log.info("拉取消息: sessionId={}", sessionId);
        return Result.success(chatService.listMessages(sessionId));
    }
}
