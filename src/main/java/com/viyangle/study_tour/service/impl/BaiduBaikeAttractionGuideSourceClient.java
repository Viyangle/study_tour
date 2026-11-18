package com.viyangle.study_tour.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.pojo.AttractionGuideSource;
import com.viyangle.study_tour.service.AttractionGuideSourceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * 可选的百度百科摘要源。旧卡片接口没有可核实的现行开放 SLA，且内容再利用需要授权，
 * 因此默认关闭；只有部署方配置自己的 APPID 并明确启用后才调用。
 */
@Slf4j
@Component
@Order(4)
public class BaiduBaikeAttractionGuideSourceClient implements AttractionGuideSourceClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final boolean enabled;
    private final String appId;
    private final String endpoint;
    private final int timeoutSeconds;
    private final HttpClient httpClient;

    @Autowired
    public BaiduBaikeAttractionGuideSourceClient(
            @Value("${app.guide.sources.baidu-enabled:false}") boolean enabled,
            @Value("${app.guide.sources.baidu-app-id:}") String appId,
            @Value("${app.guide.sources.baidu-endpoint:https://baike.baidu.com/api/openapi/BaikeLemmaCardApi}") String endpoint,
            @Value("${app.guide.sources.timeout-seconds:12}") int timeoutSeconds) {
        this.enabled = enabled;
        this.appId = appId;
        this.endpoint = endpoint;
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(this.timeoutSeconds)).build();
    }

    @Override
    public Optional<AttractionGuideSource> fetch(Attraction attraction) {
        if (!enabled || appId == null || appId.isBlank()) {
            return Optional.empty();
        }
        for (String query : AttractionGuideSourceMatcher.queryCandidates(attraction)) {
            try {
                String url = endpoint + "?scope=103&format=json&bk_length=1200"
                        + "&appid=" + encode(appId) + "&bk_key=" + encode(query);
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .header("Accept", "application/json")
                        .header("User-Agent", "StudyTourGuide/1.0 (authorized content sync)")
                        .GET().build();
                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 200) {
                    log.warn("百度百科讲解资料 HTTP 异常: poiId={}, status={}", attraction.getPoiId(), response.statusCode());
                    continue;
                }
                JsonNode root = MAPPER.readTree(response.body());
                String text = root.path("abstract").asText("").trim();
                String title = root.path("title").asText("").trim();
                if (!AttractionGuideSourceMatcher.isRelevant(attraction, query, title, text)) {
                    log.info("百度百科同名资料已拒绝: poiId={}, query={}, matchedTitle={}",
                            attraction.getPoiId(), query, title);
                    continue;
                }
                return Optional.of(AttractionGuideSource.builder()
                        .sourceType("BAIDU_BAIKE")
                        .sourceName("百度百科（授权内容源）")
                        .sourceUrl(root.path("url").asText("").replaceFirst("^http://", "https://"))
                        .text(text)
                        .matchedTitle(title)
                        .revision(root.path("newLemmaId").asText(""))
                        .license("按部署方取得的内容授权")
                        .attribution("百度百科；内容经整理或 AI 改写")
                        .build());
            } catch (Exception e) {
                log.warn("百度百科讲解资料查询失败: poiId={}, query={}, {}",
                        attraction.getPoiId(), query, e.getMessage());
            }
        }
        return Optional.empty();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
