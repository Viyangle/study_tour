package com.viyangle.study_tour.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.pojo.AttractionGuideSource;
import com.viyangle.study_tour.service.AttractionGuideSourceClient;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MediaWiki Action API 通用适配器。调用方提供站点标签和许可证，正文仍需经过标题/地理校验。
 */
@Slf4j
public class MediaWikiAttractionGuideSourceClient implements AttractionGuideSourceClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_SOURCE_CHARS = 16000;

    private final String endpoint;
    private final int timeoutSeconds;
    private final long minRequestIntervalMillis;
    private final String sourceType;
    private final String sourceName;
    private final String license;
    private final String attribution;
    private final HttpClient httpClient;
    private long lastRequestAt;

    protected MediaWikiAttractionGuideSourceClient(String endpoint,
                                                   int timeoutSeconds,
                                                   long minRequestIntervalMillis,
                                                   String sourceType,
                                                   String sourceName,
                                                   String license,
                                                   String attribution,
                                                   HttpClient httpClient) {
        this.endpoint = endpoint;
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.minRequestIntervalMillis = Math.max(0, minRequestIntervalMillis);
        this.sourceType = sourceType;
        this.sourceName = sourceName;
        this.license = license;
        this.attribution = attribution;
        this.httpClient = httpClient;
    }

    @Override
    public Optional<AttractionGuideSource> fetch(Attraction attraction) {
        List<String> candidates = AttractionGuideSourceMatcher.queryCandidates(attraction);
        for (String candidate : candidates) {
            Optional<AttractionGuideSource> direct = queryPage(attraction, candidate, candidate);
            if (direct.isPresent()) {
                return direct;
            }
        }
        // 直接标题未命中时才搜索；不能直接采用 search[0]，每个候选都要重新校验。
        for (String candidate : candidates) {
            for (String title : searchTitles(candidate)) {
                Optional<AttractionGuideSource> result = queryPage(attraction, candidate, title);
                if (result.isPresent()) {
                    return result;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<AttractionGuideSource> queryPage(Attraction attraction, String query, String title) {
        return queryPage(attraction, query, title, true);
    }

    private Optional<AttractionGuideSource> queryPage(Attraction attraction, String query,
                                                      String title, boolean resolveDisambiguation) {
        try {
            String url = endpoint + "?action=query&format=json&formatversion=2&redirects=1&maxlag=5"
                    + "&prop=extracts%7Cinfo%7Ccoordinates%7Cpageprops%7Crevisions"
                    + "&explaintext=1&inprop=url&rvprop=ids%7Ctimestamp&ppprop=wikibase_item"
                    + "&titles=" + encode(title);
            JsonNode root = getJson(url);
            JsonNode pages = root.path("query").path("pages");
            if (!pages.isArray() || pages.isEmpty()) {
                return Optional.empty();
            }
            JsonNode page = pages.get(0);
            if (page.has("missing")) {
                return Optional.empty();
            }
            String text = page.path("extract").asText("").trim();
            String matchedTitle = page.path("title").asText("").trim();
            boolean disambiguation = page.has("disambiguation")
                    || text.contains("可以指") && text.length() < 500;
            if (disambiguation && resolveDisambiguation) {
                Optional<AttractionGuideSource> resolved = resolveDisambiguation(
                        attraction, query, title, text);
                if (resolved.isPresent()) {
                    return resolved;
                }
                return Optional.empty();
            }
            boolean genericDefinition = matchedTitle.length() <= 8
                    && text.startsWith(matchedTitle)
                    && text.substring(0, Math.min(120, text.length())).contains("是指");
            if (text.isBlank() || text.length() < 40 || disambiguation || genericDefinition
                    || coordinatesConflict(attraction, page.path("coordinates"))
                    || !AttractionGuideSourceMatcher.isRelevant(attraction, query, matchedTitle, text)) {
                log.debug("百科条目相关性校验未通过: poiId={}, query={}, title={}",
                        attraction.getPoiId(), query, matchedTitle);
                return Optional.empty();
            }

            String revision = "";
            JsonNode revisions = page.path("revisions");
            if (revisions.isArray() && !revisions.isEmpty()) {
                revision = revisions.get(0).path("revid").asText("");
            }
            return Optional.of(AttractionGuideSource.builder()
                    .sourceType(sourceType)
                    .sourceName(sourceName)
                    .sourceUrl(page.path("fullurl").asText(""))
                    .text(truncate(text))
                    .matchedTitle(matchedTitle)
                    .revision(revision)
                    .license(license)
                    .attribution(attribution)
                    .build());
        } catch (Exception e) {
            log.warn("{}讲解资料查询失败: poiId={}, title={}, {}", sourceName,
                    attraction.getPoiId(), title, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<AttractionGuideSource> resolveDisambiguation(Attraction attraction, String query,
                                                                  String title, String disambiguationText) {
        String city = attraction.getCityname() == null ? "" : attraction.getCityname().replace("市", "");
        for (String line : disambiguationText.split("\\R")) {
            String candidate = line.trim().replaceAll("[，,。；;：:].*$", "").trim();
            if (candidate.length() <= query.length() || candidate.equals(title)
                    || city.isBlank() || !candidate.contains(city)) {
                continue;
            }
            Optional<AttractionGuideSource> resolved = queryPage(attraction, query, candidate, false);
            if (resolved.isPresent()) {
                return resolved;
            }
        }
        return Optional.empty();
    }

    private List<String> searchTitles(String query) {
        try {
            String url = endpoint + "?action=query&format=json&formatversion=2&maxlag=5"
                    + "&list=search&srlimit=5&srprop=snippet&srsearch=" + encode(query);
            JsonNode search = getJson(url).path("query").path("search");
            List<String> titles = new ArrayList<>();
            if (search.isArray()) {
                for (JsonNode item : search) {
                    String title = item.path("title").asText("").trim();
                    if (!title.isBlank()) {
                        titles.add(title);
                    }
                }
            }
            return titles;
        } catch (Exception e) {
            log.warn("{}条目搜索失败: query={}, {}", sourceName, query, e.getMessage());
            return List.of();
        }
    }

    private JsonNode getJson(String url) throws IOException, InterruptedException {
        throttle();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Accept", "application/json")
                .header("User-Agent", "StudyTourGuide/1.0 (educational research project; backend sync)")
                .GET().build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException(sourceName + " HTTP " + response.statusCode());
        }
        return MAPPER.readTree(response.body());
    }

    private synchronized void throttle() throws InterruptedException {
        long wait = minRequestIntervalMillis - (System.currentTimeMillis() - lastRequestAt);
        if (wait > 0) {
            Thread.sleep(wait);
        }
        lastRequestAt = System.currentTimeMillis();
    }

    private boolean coordinatesConflict(Attraction attraction, JsonNode coordinates) {
        if (attraction.getLocation() == null || !coordinates.isArray() || coordinates.isEmpty()) {
            return false;
        }
        String[] parts = attraction.getLocation().split(",");
        if (parts.length != 2) {
            return false;
        }
        try {
            double poiLng = Double.parseDouble(parts[0]);
            double poiLat = Double.parseDouble(parts[1]);
            double sourceLat = coordinates.get(0).path("lat").asDouble();
            double sourceLng = coordinates.get(0).path("lon").asDouble();
            return distanceMeters(poiLat, poiLng, sourceLat, sourceLng) > 10000;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double earthRadius = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static String truncate(String text) {
        return text.length() <= MAX_SOURCE_CHARS ? text : text.substring(0, MAX_SOURCE_CHARS);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
