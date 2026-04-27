package com.viyangle.study_tour.tools;

import com.viyangle.study_tour.pojo.ReferenceRouteDetail;
import com.viyangle.study_tour.service.ReferenceRouteService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ReferenceRouteTool {

    @Autowired
    private ReferenceRouteService referenceRouteService;

    @Tool("Search and return reference routes with attraction details")
    public List<ReferenceRouteDetail> searchReferenceRoutes(
            @P("Account id for preference-based recommendation, nullable") Long accountId,
            @P("Page number, starts from 1") Integer pageNum,
            @P("Page size") Integer pageSize) {
        return referenceRouteService.recommendReferenceRoutes(accountId, pageNum, pageSize);
    }
}
