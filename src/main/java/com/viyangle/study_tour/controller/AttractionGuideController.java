package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.exception.ResourceNotFoundException;
import com.viyangle.study_tour.pojo.AttractionGuide;
import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.service.AttractionGuideService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/attractions")
public class AttractionGuideController {

    private final AttractionGuideService attractionGuideService;

    public AttractionGuideController(AttractionGuideService attractionGuideService) {
        this.attractionGuideService = attractionGuideService;
    }

    /**
     * 查询已发布的景点研学讲解；内容由离线整理或生成后入库，本接口不调用 AI。
     */
    @GetMapping("/{poiId}/guide")
    public Result getPublishedGuide(@PathVariable String poiId) {
        AttractionGuide guide = attractionGuideService.getPublishedGuideByPoiId(poiId);
        if (guide == null) {
            throw new ResourceNotFoundException("景点研学讲解不存在或未发布, poiId=" + poiId);
        }
        return Result.success(guide);
    }
}
