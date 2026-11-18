package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.annotation.OperationLog;
import com.viyangle.study_tour.annotation.RequireRole;
import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.service.AttractionGuideSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 景点讲解自动同步的运维入口。单景点同步和定时扫描不需要调用这里。
 */
@Slf4j
@RestController
@RequestMapping("/admin/attraction-guides")
public class AttractionGuideAdminController {

    private final AttractionGuideSyncService attractionGuideSyncService;

    public AttractionGuideAdminController(AttractionGuideSyncService attractionGuideSyncService) {
        this.attractionGuideSyncService = attractionGuideSyncService;
    }

    @PostMapping("/backfill")
    @RequireRole({"ADMIN"})
    @OperationLog(value = "补齐景点研学讲解", type = "ATTRACTION_GUIDE_BACKFILL")
    public Result backfill(@RequestParam(defaultValue = "50") Integer limit) {
        int size = limit == null ? 50 : Math.max(1, Math.min(limit, 500));
        int synced = attractionGuideSyncService.backfillMissing(size);
        log.info("景点讲解补录完成: requested={}, synced={}", size, synced);
        return Result.success(synced);
    }

    @PostMapping("/resync")
    @RequireRole({"ADMIN"})
    @OperationLog(value = "强制重算景点研学讲解", type = "ATTRACTION_GUIDE_RESYNC")
    public Result resync(@RequestParam(defaultValue = "50") Integer limit) {
        int size = limit == null ? 50 : Math.max(1, Math.min(limit, 500));
        int synced = attractionGuideSyncService.resyncAll(size);
        log.info("景点讲解强制重算完成: requested={}, synced={}", size, synced);
        return Result.success(synced);
    }
}
