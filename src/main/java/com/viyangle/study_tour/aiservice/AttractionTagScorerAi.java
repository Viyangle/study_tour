package com.viyangle.study_tour.aiservice;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

/**
 * 景点 15 维标签自动打分。使用与路线规划相同的 OpenAI 兼容模型，
 * 输出 JSON；调用失败时由 {@code TagVectorLabelService} 回退到规则打分。
 */
@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "openAiChatModel"
)
public interface AttractionTagScorerAi {

    @SystemMessage(fromResource = "system-attraction-tag-score.txt")
    String score(@UserMessage String attractionInfo);
}
