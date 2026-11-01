package com.viyangle.study_tour.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.viyangle.study_tour.agent.dto.PlanRequest;
import com.viyangle.study_tour.mapper.AttractionMapper;
import com.viyangle.study_tour.service.KnowledgeGraphRecommendService;
import com.viyangle.study_tour.service.ReferencePairService;
import com.viyangle.study_tour.service.VectorCandidateRetrieverService;
import com.viyangle.study_tour.utils.AmapDirectionClient;
import com.viyangle.study_tour.utils.AmapTransitClient;
import org.springframework.stereotype.Component;

@Component
public record RouteAgentToolsFactory(ObjectMapper mapper, AttractionMapper attractions,
                                    VectorCandidateRetrieverService retriever, KnowledgeGraphRecommendService graph,
                                    ReferencePairService references, AmapTransitClient places,
                                    AmapDirectionClient directions) {
    public RouteAgentTools create(PlanRequest request, AgentExecutionBudget budget) {
        return new RouteAgentTools(request, budget, mapper, attractions, retriever, graph, references, places, directions);
    }
}
