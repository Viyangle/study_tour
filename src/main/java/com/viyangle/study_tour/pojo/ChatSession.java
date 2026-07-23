package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatSession {
    private Long id;
    private Long projectId;
    private Long userAccountId;
    private Long leaderAccountId;
    private String status;
    private LocalDateTime disabledAt;
    private LocalDateTime createdAt;
    private String projectTitle;
    private String latestMessage;
    private LocalDateTime latestMessageAt;
    private Long latestMessageSenderAccountId;
}
