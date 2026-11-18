package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.pojo.AttractionGuideSource;
import com.viyangle.study_tour.service.AttractionGuideSourceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 百度百科移动页的摘要 fallback。
 *
 * <p>该来源没有可核实的现行开放 API/SLA，内容再利用也需要部署方自行确认授权，
 * 因此配置为可关闭的补充来源；只读取页面 meta 中的标题、摘要和规范 URL，不抓正文。</p>
 */
@Slf4j
@Component
@Order(3)
public class BaiduBaikeMobileAttractionGuideSourceClient implements AttractionGuideSourceClient {

    private static final Pattern META_TAG = Pattern.compile("<meta\\b[^>]*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CONTENT_ATTRIBUTE = Pattern.compile(
            "content=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final boolean enabled;
    private final String endpoint;
    private final int timeoutSeconds;
    private final HttpClient httpClient;

    @Autowired
    public BaiduBaikeMobileAttractionGuideSourceClient(
            @Value("${app.guide.sources.baidu-mobile-enabled:true}") boolean enabled,
            @Value("${app.guide.sources.baidu-mobile-endpoint:https://wapbaike.baidu.com/item/}") String endpoint,
            @Value("${app.guide.sources.timeout-seconds:12}") int timeoutSeconds) {
        this(enabled, endpoint, timeoutSeconds,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds))).build());
    }

    public BaiduBaikeMobileAttractionGuideSourceClient(boolean enabled, String endpoint, int timeoutSeconds,
                                                       HttpClient httpClient) {
        this.enabled = enabled;
        this.endpoint = endpoint.endsWith("/") ? endpoint : endpoint + "/";
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.httpClient = httpClient;
    }

    @Override
    public Optional<AttractionGuideSource> fetch(Attraction attraction) {
        if (!enabled) {
            return Optional.empty();
        }
        for (String query : AttractionGuideSourceMatcher.queryCandidates(attraction)) {
            try {
                String url = endpoint + URLEncoder.encode(query, StandardCharsets.UTF_8);
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .header("Accept", "text/html,application/xhtml+xml")
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) "
                                + "AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                        .GET().build();
                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 200) {
                    continue;
                }
                String text = HtmlUtils.htmlUnescape(
                        longestMetaContent(response.body(), "name", "description")).trim();
                String title = HtmlUtils.htmlUnescape(metaContent(response.body(), "property", "og:title")).trim();
                String canonicalUrl = HtmlUtils.htmlUnescape(metaContent(response.body(), "property", "og:url")).trim();
                if (text.isBlank() || title.isBlank()
                        || !AttractionGuideSourceMatcher.isRelevant(attraction, query, title, text)) {
                    log.debug("百度移动百科资料已拒绝: poiId={}, query={}, matchedTitle={}, textLength={}",
                            attraction.getPoiId(), query, title, text.length());
                    continue;
                }
                return Optional.of(AttractionGuideSource.builder()
                        .sourceType("BAIDU_BAIKE_MOBILE")
                        .sourceName("百度百科（移动页）")
                        .sourceUrl(canonicalUrl.isBlank() ? url : canonicalUrl)
                        .text(text)
                        .matchedTitle(title)
                        .license("百度百科页面引用；批量再利用需部署方确认授权")
                        .attribution("百度百科；内容经整理或 AI 改写")
                        .build());
            } catch (Exception e) {
                log.debug("百度移动百科查询失败: poiId={}, query={}, {}",
                        attraction.getPoiId(), query, e.getMessage());
            }
        }
        return Optional.empty();
    }

    /** meta 属性顺序并不稳定，逐标签解析 name/property 与 content。 */
    private static String metaContent(String html, String attribute, String value) {
        String result = "";
        Matcher tags = META_TAG.matcher(html);
        Pattern key = Pattern.compile(attribute + "=[\"']" + Pattern.quote(value) + "[\"']",
                Pattern.CASE_INSENSITIVE);
        while (tags.find()) {
            String tag = tags.group();
            if (!key.matcher(tag).find()) {
                continue;
            }
            Matcher content = CONTENT_ATTRIBUTE.matcher(tag);
            if (content.find()) {
                result = content.group(1);
                break;
            }
        }
        return result;
    }

    /** 页面可能先出现站点级 description，正文摘要取最长的一条。 */
    private static String longestMetaContent(String html, String attribute, String value) {
        String result = "";
        Matcher tags = META_TAG.matcher(html);
        Pattern key = Pattern.compile(attribute + "=[\"']" + Pattern.quote(value) + "[\"']",
                Pattern.CASE_INSENSITIVE);
        while (tags.find()) {
            String tag = tags.group();
            if (!key.matcher(tag).find()) {
                continue;
            }
            Matcher content = CONTENT_ATTRIBUTE.matcher(tag);
            if (content.find() && content.group(1).length() > result.length()) {
                result = content.group(1);
            }
        }
        return result;
    }
}
