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

    @Tool("Search and return reference attraction pairs by tag. Allowed tags: 历史人文、博物馆研学、非遗体验、科技探索、自然生态、地理地质、航天航空、农耕劳动、艺术美育、红色教育、高校参访、职业启蒙、英语实践、摄影记录、亲子互动.")
    public List<ReferencePair> searchReferencePairs(
            @P("Tag for recommendation. Allowed values: 历史人文、博物馆研学、非遗体验、科技探索、自然生态、地理地质、航天航空、农耕劳动、艺术美育、红色教育、高校参访、职业启蒙、英语实践、摄影记录、亲子互动. Pass null or empty to disable tag filtering.") String tag,
            @P("Page number, starts from 1") Integer pageNum,
            @P("Page size") Integer pageSize) {
        return referencePairService.recommendReferencePairs(tag, pageNum, pageSize);
    }
}
