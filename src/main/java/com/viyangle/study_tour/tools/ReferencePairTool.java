package com.viyangle.study_tour.tools;

import com.viyangle.study_tour.pojo.ReferencePair;
import com.viyangle.study_tour.service.ReferencePairService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ReferencePairTool {

    @Autowired
    private ReferencePairService referencePairService;

    @Tool("Search and return reference attraction pairs")
    public List<ReferencePair> searchReferencePairs(
            @P("Account id for preference-based recommendation, nullable") Long accountId,
            @P("Page number, starts from 1") Integer pageNum,
            @P("Page size") Integer pageSize) {
        return referencePairService.recommendReferencePairs(accountId, pageNum, pageSize);
    }
}
