package com.viyangle.study_tour.aiservice;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "openAiChatModel",
        tools = "referenceRouteTool"
)
public interface RouteComposerService {

    @SystemMessage(fromResource = "system-route-compose.txt")
    String chat(@UserMessage String message);
}

