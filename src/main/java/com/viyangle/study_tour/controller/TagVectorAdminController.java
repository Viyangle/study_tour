package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.annotation.OperationLog;
import com.viyangle.study_tour.annotation.RequireRole;
import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.service.TagVectorLabelService;
import com.viyangle.study_tour.service.TagVectorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 标签向量的运维入口：补标历史景点、强制重算单个景点。
 * 新景点由高德同步链路自动打分，不需要调用这里。
 */
@Slf4j
@RestController
@RequestMapping("/admin/tag-vectors")
public class TagVectorAdminController {

    @Autowired
    private TagVectorLabelService tagVectorLabelService;

    @Autowired
    private TagVectorService tagVectorService;

    @PostMapping("/backfill")
    @RequireRole({"ADMIN"})
    @OperationLog(value = "补齐景点标签向量", type = "TAG_VECTOR_BACKFILL")
    public Result backfill(@RequestParam(defaultValue = "50") Integer limit) {
        int size = limit == null ? 50 : Math.max(1, Math.min(limit, 500));
        int labeled = tagVectorLabelService.backfillMissing(size);
        tagVectorService.reload();
        log.info("标签向量补标完成: requested={}, labeled={}", size, labeled);
        return Result.success(labeled);
    }

    @PostMapping("/{poiId}/relabel")
    @RequireRole({"ADMIN"})
    @OperationLog(value = "重算景点标签向量", type = "TAG_VECTOR_RELABEL")
    public Result relabel(@PathVariable String poiId) {
        int dims = tagVectorLabelService.relabel(poiId);
        log.info("标签向量重算完成: poiId={}, dims={}", poiId, dims);
        return Result.success(dims);
    }
}
