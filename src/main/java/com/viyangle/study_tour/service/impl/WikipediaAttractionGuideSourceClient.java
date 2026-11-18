package com.viyangle.study_tour.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Duration;

/** 中文维基百科，作为求闻百科之后的第二开放内容源。 */
@Component
@Order(2)
public class WikipediaAttractionGuideSourceClient extends MediaWikiAttractionGuideSourceClient {

    private final boolean enabled;

    @Autowired
    public WikipediaAttractionGuideSourceClient(
            @Value("${app.guide.sources.wikipedia-enabled:false}") boolean enabled,
            @Value("${app.guide.sources.wikipedia-endpoint:https://zh.wikipedia.org/w/api.php}") String endpoint,
            @Value("${app.guide.sources.timeout-seconds:12}") int timeoutSeconds,
            @Value("${app.guide.sources.request-interval-ms:150}") long minRequestIntervalMillis) {
        this(enabled, endpoint, timeoutSeconds, minRequestIntervalMillis,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds))).build());
    }

    WikipediaAttractionGuideSourceClient(String endpoint, int timeoutSeconds,
                                         long minRequestIntervalMillis) {
        this(true, endpoint, timeoutSeconds, minRequestIntervalMillis,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds))).build());
    }

    WikipediaAttractionGuideSourceClient(String endpoint, int timeoutSeconds,
                                         long minRequestIntervalMillis, HttpClient httpClient) {
        this(true, endpoint, timeoutSeconds, minRequestIntervalMillis, httpClient);
    }

    public WikipediaAttractionGuideSourceClient(boolean enabled, String endpoint, int timeoutSeconds,
                                                long minRequestIntervalMillis, HttpClient httpClient) {
        super(endpoint, timeoutSeconds, minRequestIntervalMillis,
                "WIKIPEDIA", "中文维基百科", "CC BY-SA 4.0",
                "中文维基百科编者；内容经整理或 AI 改写，依 CC BY-SA 4.0 提供",
                httpClient);
        this.enabled = enabled;
    }

    @Override
    public java.util.Optional<com.viyangle.study_tour.pojo.AttractionGuideSource> fetch(
            com.viyangle.study_tour.pojo.Attraction attraction) {
        return enabled ? super.fetch(attraction) : java.util.Optional.empty();
    }
}
