package com.viyangle.study_tour.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 求闻百科，国内可直连的 MediaWiki 站点。文字采用 CC BY-SA 4.0，
 * API 使用导则要求串行、合理速率和描述性 User-Agent。
 */
@Component
@Order(1)
public class QiuwenAttractionGuideSourceClient extends MediaWikiAttractionGuideSourceClient {

    @Autowired
    public QiuwenAttractionGuideSourceClient(
            @Value("${app.guide.sources.qiuwen-endpoint:https://www.qiuwenbaike.cn/api.php}") String endpoint,
            @Value("${app.guide.sources.timeout-seconds:12}") int timeoutSeconds,
            @Value("${app.guide.sources.request-interval-ms:150}") long minRequestIntervalMillis) {
        this(endpoint, timeoutSeconds, minRequestIntervalMillis,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds))).build());
    }

    QiuwenAttractionGuideSourceClient(String endpoint, int timeoutSeconds,
                                      long minRequestIntervalMillis, HttpClient httpClient) {
        super(endpoint, timeoutSeconds, minRequestIntervalMillis,
                "QIUWEN", "求闻百科", "CC BY-SA 4.0",
                "求闻百科编者；内容经整理或 AI 改写，依 CC BY-SA 4.0 提供",
                httpClient);
    }
}
