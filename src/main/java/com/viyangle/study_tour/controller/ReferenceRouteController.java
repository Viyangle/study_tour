package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.pojo.ReferenceRouteAttraction;
import com.viyangle.study_tour.pojo.ReferenceRouteDetail;
import com.viyangle.study_tour.pojo.ReferenceRouteRecommendRequest;
import com.viyangle.study_tour.pojo.ReferenceRouteUpsertRequest;
import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.service.ReferenceRouteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/reference-routes")
public class ReferenceRouteController {

    @Autowired
    private ReferenceRouteService referenceRouteService;

    @GetMapping
    public Result getAllReferenceRoutes(@RequestParam(defaultValue = "1") Integer pageNum,
                                        @RequestParam(defaultValue = "10") Integer pageSize,
                                        @RequestParam(required = false) Long accountId) {
        log.info("分页获取参考路线, accountId={}, pageNum={}, pageSize={}", accountId, pageNum, pageSize);
        return Result.success(referenceRouteService.getPagedReferenceRoutesByPreference(accountId, pageNum, pageSize));
    }

    @GetMapping("/{id}")
    public Result getReferenceRouteById(@PathVariable Long id) {
        log.info("Get reference route: {}", id);
        return Result.success(referenceRouteService.getReferenceRouteDetailById(id));
    }

    @PostMapping
    public Result createReferenceRoute(@RequestBody ReferenceRouteUpsertRequest request) {
        log.info("Create reference route");
        Long routeId = referenceRouteService.generateReferenceRouteByManual(request.getTag(), request.getRouteAttractions());
        return Result.success(routeId);
    }

    @PutMapping("/{id}")
    public Result updateReferenceRoute(@PathVariable Long id, @RequestBody ReferenceRouteUpsertRequest request) {
        log.info("Update reference route, id={}", id);
        referenceRouteService.updateReferenceRouteByManual(id, request.getTag(), request.getRouteAttractions());
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result deleteReferenceRoute(@PathVariable Long id) {
        log.info("Delete reference route, id={}", id);
        referenceRouteService.deleteReferenceRouteById(id);
        return Result.success();
    }

    @PostMapping("/recommend")
    public Result recommendReferenceRoutes(@RequestBody(required = false) ReferenceRouteRecommendRequest request) {
        Long accountId = request == null ? null : request.getAccountId();
        Integer pageNum = request == null ? 1 : request.getPageNum();
        Integer pageSize = request == null ? 10 : request.getPageSize();
        List<ReferenceRouteDetail> data = referenceRouteService.recommendReferenceRoutes(accountId, pageNum, pageSize);
        return Result.success(data);
    }
}
