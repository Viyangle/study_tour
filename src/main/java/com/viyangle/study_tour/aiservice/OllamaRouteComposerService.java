package com.viyangle.study_tour.aiservice;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

/**
 * Ollama 专用路线编排服务，不使用 tools（Ollama 对 function calling 支持有限）。
 * 使用时需将 application.yaml 中 langchain4j.open-ai.chat-model 指向 Ollama 地址。
 */
@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "openAiChatModel"
)
public interface OllamaRouteComposerService {

    @SystemMessage(fromResource = "system-route-compose.txt")
    String chat(@UserMessage String message);
}
