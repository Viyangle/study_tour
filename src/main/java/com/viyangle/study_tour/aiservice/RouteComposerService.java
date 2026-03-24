package com.viyangle.study_tour.aiservice;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "openAiChatModel",
        chatMemory = "chatMemory",
        chatMemoryProvider = "chatMemoryProvider"
)
public interface RouteComposerService {

    @SystemMessage(fromResource = "system-route-compose.txt")
    String chat(@MemoryId String memoryId, @UserMessage String message);
}

