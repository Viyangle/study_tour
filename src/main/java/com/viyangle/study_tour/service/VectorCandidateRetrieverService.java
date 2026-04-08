package com.viyangle.study_tour.service;

import com.viyangle.study_tour.pojo.VectorRetrievalResult;

import java.util.List;

public interface VectorCandidateRetrieverService {
    List<String> retrieveCandidatePoiIds(String message, int limit);

    VectorRetrievalResult retrieveCandidatesWithTexts(String message, int poiLimit, int textLimit);
}
