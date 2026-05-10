package com.viyangle.study_tour.service.impl;

import com.github.pagehelper.PageHelper;
import com.viyangle.study_tour.exception.ResourceNotFoundException;
import com.viyangle.study_tour.mapper.ReferencePairMapper;
import com.viyangle.study_tour.pojo.ReferencePair;
import com.viyangle.study_tour.service.ReferencePairService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ReferencePairServiceImpl implements ReferencePairService {

    @Autowired
    private ReferencePairMapper referencePairMapper;

    @Transactional
    @Override
    public Long createReferencePair(ReferencePair referencePair) {
        validateReferencePair(referencePair);
        referencePair.setId(null);
        referencePair.setCreatedAt(LocalDateTime.now());
        referencePairMapper.insert(referencePair);
        return referencePair.getId();
    }

    @Transactional
    @Override
    public void updateReferencePair(Long id, ReferencePair referencePair) {
        ReferencePair existing = referencePairMapper.selectById(id);
        if (existing == null) {
            throw new ResourceNotFoundException("reference pair not found, id=" + id);
        }
        validateReferencePair(referencePair);
        ReferencePair update = new ReferencePair();
        update.setId(id);
        update.setTag(referencePair.getTag());
        update.setNotes(referencePair.getNotes());
        update.setRegionAdcode(referencePair.getRegionAdcode());
        update.setFromPoiId(referencePair.getFromPoiId());
        update.setFromPoiName(referencePair.getFromPoiName());
        update.setToPoiId(referencePair.getToPoiId());
        update.setToPoiName(referencePair.getToPoiName());
        referencePairMapper.updateById(update);
    }

    @Transactional
    @Override
    public void deleteReferencePair(Long id) {
        referencePairMapper.deleteById(id);
    }

    @Override
    public ReferencePair getReferencePairById(Long id) {
        ReferencePair pair = referencePairMapper.selectById(id);
        if (pair == null) {
            throw new ResourceNotFoundException("reference pair not found, id=" + id);
        }
        return pair;
    }

    @Override
    public List<ReferencePair> getPagedReferencePairsByTag(String tag, Integer pageNum, Integer pageSize) {
        int page = (pageNum == null || pageNum < 1) ? 1 : pageNum;
        int size = (pageSize == null || pageSize < 1) ? 10 : pageSize;
        PageHelper.startPage(page, size);
        return referencePairMapper.selectByTag(normalizeTag(tag));
    }

    @Override
    public List<ReferencePair> recommendReferencePairs(String tag, Integer pageNum, Integer pageSize) {
        return getPagedReferencePairsByTag(tag, pageNum, pageSize);
    }

    private void validateReferencePair(ReferencePair referencePair) {
        if (referencePair == null) {
            throw new IllegalArgumentException("referencePair cannot be null");
        }
        if (referencePair.getFromPoiId() == null || referencePair.getFromPoiId().isBlank()) {
            throw new IllegalArgumentException("fromPoiId cannot be empty");
        }
        if (referencePair.getToPoiId() == null || referencePair.getToPoiId().isBlank()) {
            throw new IllegalArgumentException("toPoiId cannot be empty");
        }
        if (referencePair.getFromPoiName() == null || referencePair.getFromPoiName().isBlank()) {
            throw new IllegalArgumentException("fromPoiName cannot be empty");
        }
        if (referencePair.getToPoiName() == null || referencePair.getToPoiName().isBlank()) {
            throw new IllegalArgumentException("toPoiName cannot be empty");
        }
        if (referencePair.getTag() == null || referencePair.getTag().isBlank()) {
            throw new IllegalArgumentException("tag cannot be empty");
        }
    }

    private String normalizeTag(String tag) {
        if (tag == null) {
            return null;
        }
        String normalized = tag.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
