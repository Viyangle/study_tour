package com.viyangle.study_tour.service;

import com.viyangle.study_tour.pojo.ReferencePair;

import java.util.List;

public interface ReferencePairService {

    Long createReferencePair(ReferencePair referencePair);

    void updateReferencePair(Long id, ReferencePair referencePair);

    void deleteReferencePair(Long id);

    ReferencePair getReferencePairById(Long id);

    List<ReferencePair> getPagedReferencePairsByPreference(Long accountId, Integer pageNum, Integer pageSize);

    List<ReferencePair> recommendReferencePairs(Long accountId, Integer pageNum, Integer pageSize);
}
