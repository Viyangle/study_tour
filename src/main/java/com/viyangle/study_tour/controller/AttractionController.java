package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.annotation.RequireRole;
import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.service.AttractionService;
import com.viyangle.study_tour.service.AttractionSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/attractions")
public class AttractionController {

    @Autowired
    private AttractionService attractionService;

    @Autowired
    private AttractionSyncService attractionSyncService;

    @GetMapping
    public Result getAllAttractions(@RequestParam(required = false) String regionCode,
                                    @RequestParam(defaultValue = "1") Integer pageNum,
                                    @RequestParam(defaultValue = "10") Integer pageSize) {
        log.info("按地区分页获取景点, regionCode={}, pageNum={}, pageSize={}", regionCode, pageNum, pageSize);
        return Result.success(attractionService.getPagedAttractionsByRegion(regionCode, pageNum, pageSize));
    }

    /**
     * 按高德 poiId 同步单个景点：高德 place/detail -> MySQL upsert -> Redis 向量索引增量更新。
     */
    @PostMapping("/sync/{poiId}")
    public Result syncAttractionFromAmap(@PathVariable String poiId) {
        log.info("同步高德景点, poiId={}", poiId);
        return Result.success(attractionSyncService.syncFromAmap(poiId));
    }

    /**
     * 全量重建 Redis 向量索引：先清空，再按数据库当前有效景点重建。
     * 适合手动/定期同步数据库后调用。
     */
    @PostMapping("/reindex")
    @RequireRole({"ADMIN"})
    public Result reindexAttractionVector() {
        log.info("全量重建景点向量索引");
        return Result.success(attractionSyncService.reindexAll());
    }
}
