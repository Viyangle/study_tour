package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.mapper.AttractionGuideMapper;
import com.viyangle.study_tour.mapper.AttractionMapper;
import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.pojo.AttractionGuide;
import com.viyangle.study_tour.pojo.AttractionGuideSource;
import com.viyangle.study_tour.service.AttractionGuideSourceClient;
import com.viyangle.study_tour.service.AttractionGuideSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class AttractionGuideSyncServiceImpl implements AttractionGuideSyncService {

    private final AttractionGuideMapper attractionGuideMapper;
    private final AttractionMapper attractionMapper;
    private final List<AttractionGuideSourceClient> sourceClients;
    private final AttractionGuideContentGenerator contentGenerator;
    private final ConcurrentHashMap<String, Object> poiLocks = new ConcurrentHashMap<>();

    @Value("${app.guide.enabled:true}")
    private boolean enabled = true;

    @Value("${app.guide.scheduled-sync-enabled:true}")
    private boolean scheduledSyncEnabled = true;

    @Value("${app.guide.refresh-days:30}")
    private int refreshDays = 30;

    @Value("${app.guide.backfill-batch-size:50}")
    private int backfillBatchSize = 50;

    @Value("${app.guide.batch-pause-ms:200}")
    private long batchPauseMillis = 200;

    public AttractionGuideSyncServiceImpl(AttractionGuideMapper attractionGuideMapper,
                                          AttractionMapper attractionMapper,
                                          List<AttractionGuideSourceClient> sourceClients,
                                          AttractionGuideContentGenerator contentGenerator) {
        this.attractionGuideMapper = attractionGuideMapper;
        this.attractionMapper = attractionMapper;
        this.sourceClients = sourceClients;
        this.contentGenerator = contentGenerator;
    }

    @Override
    public boolean syncGuide(Attraction attraction) {
        if (!enabled || attraction == null || attraction.getPoiId() == null || attraction.getPoiId().isBlank()) {
            return false;
        }
        String poiId = attraction.getPoiId().trim();
        Object lock = poiLocks.computeIfAbsent(poiId, key -> new Object());
        try {
            synchronized (lock) {
                return syncLocked(attraction, poiId);
            }
        } finally {
            poiLocks.remove(poiId, lock);
        }
    }

    private boolean syncLocked(Attraction attraction, String poiId) {
        AttractionGuide existing = attractionGuideMapper.selectByPoiId(poiId);
        if (existing != null && Boolean.FALSE.equals(existing.getAutoUpdate())) {
            log.info("景点讲解为人工维护，自动同步跳过: poiId={}", poiId);
            return false;
        }

        Attraction parent = loadParent(attraction);
        AttractionGuideSource source = findSource(attraction)
                .orElseGet(() -> findParentSource(parent).orElseGet(() -> amapSource(attraction, parent)));
        String sourceHash = sourceHash(attraction, parent, source);
        if (existing != null && sourceHash.equals(existing.getSourceHash())) {
            return false;
        }

        AttractionGuide generated = contentGenerator.generate(attraction, source, parent);
        generated.setPoiId(poiId);
        generated.setCoverUrl(source.getImageUrl() != null ? source.getImageUrl()
                : existing == null ? null : existing.getCoverUrl());
        generated.setAudioUrl(existing == null ? null : existing.getAudioUrl());
        generated.setSourceName(source.getSourceName());
        generated.setSourceUrl(source.getSourceUrl());
        generated.setSourceType(source.getSourceType());
        generated.setSourceText(source.getText());
        generated.setSourceRevision(source.getRevision());
        generated.setSourceLicense(source.getLicense());
        generated.setSourceAttribution(source.getAttribution());
        generated.setSourceHash(sourceHash);
        generated.setSyncStatus(syncStatus(source));
        generated.setAutoUpdate(true);
        generated.setSyncedAt(LocalDateTime.now());
        generated.setStatus(existing != null && existing.getStatus() != null
                ? existing.getStatus() : "PUBLISHED");

        int affected = attractionGuideMapper.upsert(generated);
        log.info("景点讲解同步完成: poiId={}, sourceType={}, ai={}, coverPreserved={}",
                poiId, source.getSourceType(), contentGenerator.usingAi(), generated.getCoverUrl() != null);
        return affected > 0;
    }

    private Optional<AttractionGuideSource> findSource(Attraction attraction) {
        for (AttractionGuideSourceClient sourceClient : sourceClients) {
            try {
                Optional<AttractionGuideSource> source = sourceClient.fetch(attraction);
                if (source != null && source.isPresent() && source.get().getText() != null
                        && !source.get().getText().isBlank()) {
                    return source;
                }
            } catch (Exception e) {
                log.warn("讲解信息源调用失败，继续尝试下一来源: poiId={}, client={}, {}",
                        attraction.getPoiId(), sourceClient.getClass().getSimpleName(), e.getMessage());
            }
        }
        return Optional.empty();
    }

    private Optional<AttractionGuideSource> findParentSource(Attraction parent) {
        if (parent == null) {
            return Optional.empty();
        }
        return findSource(parent).map(source -> {
            source.setParentSource(true);
            source.setSourceType("PARENT");
            source.setSourceName(source.getSourceName() + "（所属景点：" + parent.getName() + "）");
            source.setAttribution(source.getAttribution() == null ? null
                    : source.getAttribution() + "；当前点位使用所属景点背景资料");
            return source;
        });
    }

    private Attraction loadParent(Attraction attraction) {
        if (attraction.getParentPoiId() == null || attraction.getParentPoiId().isBlank()) {
            return null;
        }
        String parentPoiId = attraction.getParentPoiId().trim();
        if (parentPoiId.equals(attraction.getPoiId())) {
            return null;
        }
        return attractionMapper.selectByPoiId(parentPoiId);
    }

    private AttractionGuideSource amapSource(Attraction attraction, Attraction parent) {
        String text = "景点名称：" + nullSafe(attraction.getName())
                + "\n地址：" + nullSafe(attraction.getAddress())
                + "\n城市：" + nullSafe(attraction.getCityname())
                + "\n类型：" + nullSafe(attraction.getType())
                + (parent == null ? "" : "\n所属景点：" + nullSafe(parent.getName()))
                + "\n未找到通过相关性校验的公开百科资料。不得补充历史年代、人物、数据或展品信息。";
        String sourceUrl = null;
        if (attraction.getLocation() != null && attraction.getLocation().contains(",")) {
            sourceUrl = "https://uri.amap.com/marker?position=" + attraction.getLocation()
                    + "&name=" + URLEncoder.encode(attraction.getName(), StandardCharsets.UTF_8)
                    + "&coordinate=gaode&callnative=0";
        }
        return AttractionGuideSource.builder()
                .sourceType("AMAP")
                .sourceName("高德地图 POI（基础资料，待补录）")
                .sourceUrl(sourceUrl)
                .text(text)
                .attribution("高德地图 POI 基础字段；未采用百科正文")
                .build();
    }

    private String sourceHash(Attraction attraction, Attraction parent, AttractionGuideSource source) {
        String value = nullSafe(attraction.getName()) + "\n" + nullSafe(attraction.getAddress())
                + "\n" + nullSafe(attraction.getType()) + "\n" + nullSafe(attraction.getCityname())
                + "\n" + (parent == null ? "" : nullSafe(parent.getName()))
                + "\n" + nullSafe(source.getRevision()) + "\n" + nullSafe(source.getText())
                + "\n" + (contentGenerator.usingAi() ? "AI" : "TEMPLATE");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法计算讲解来源哈希", e);
        }
    }

    private String syncStatus(AttractionGuideSource source) {
        if (source.isParentSource()) {
            return "PARENT";
        }
        return "AMAP".equals(source.getSourceType()) ? "FALLBACK" : "SOURCED";
    }

    @Override
    public int backfillMissing(int limit) {
        if (!enabled) {
            return 0;
        }
        int max = limit <= 0 ? 50 : Math.min(limit, 500);
        List<String> poiIds = attractionGuideMapper.selectPoiIdsNeedingSync(max, Math.max(1, refreshDays));
        return syncPoiIds(poiIds, "回填");
    }

    @Override
    public int resyncAll(int limit) {
        if (!enabled) {
            return 0;
        }
        int max = limit <= 0 ? 50 : Math.min(limit, 500);
        return syncPoiIds(attractionGuideMapper.selectActivePoiIds(max), "强制重算");
    }

    private int syncPoiIds(List<String> poiIds, String operation) {
        if (poiIds == null || poiIds.isEmpty()) {
            log.info("景点讲解{}：没有待处理景点", operation);
            return 0;
        }
        int synced = 0;
        for (int i = 0; i < poiIds.size(); i++) {
            String poiId = poiIds.get(i);
            try {
                Attraction attraction = attractionMapper.selectByPoiId(poiId);
                if (attraction != null && syncGuide(attraction)) {
                    synced++;
                }
            } catch (Exception e) {
                log.warn("景点讲解{}失败: poiId={}, {}", operation, poiId, e.getMessage());
            }
            if (i + 1 < poiIds.size() && !pauseBetweenBatchItems()) {
                break;
            }
        }
        log.info("景点讲解{}完成：候选={}，实际更新={}", operation, poiIds.size(), synced);
        return synced;
    }

    private boolean pauseBetweenBatchItems() {
        if (batchPauseMillis <= 0) {
            return true;
        }
        try {
            Thread.sleep(batchPauseMillis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 批量脚本可能绕过 Spring 直接写 attractions，由定时扫描兜底补齐或刷新讲解。 */
    @Scheduled(initialDelayString = "${app.guide.initial-delay-ms:300000}",
            fixedDelayString = "${app.guide.sync-interval-ms:21600000}")
    public void scheduledSync() {
        if (!scheduledSyncEnabled) {
            return;
        }
        try {
            backfillMissing(backfillBatchSize);
        } catch (Exception e) {
            log.warn("定时同步景点讲解失败: {}", e.getMessage());
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
