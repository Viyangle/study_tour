package com.viyangle.study_tour.service.impl;

import com.github.pagehelper.PageHelper;
import com.viyangle.study_tour.exception.ResourceNotFoundException;
import com.viyangle.study_tour.mapper.AccountMapper;
import com.viyangle.study_tour.mapper.AccountTagPrefMapper;
import com.viyangle.study_tour.mapper.ReferencePairMapper;
import com.viyangle.study_tour.mapper.TagMapper;
import com.viyangle.study_tour.pojo.Account;
import com.viyangle.study_tour.pojo.AccountTagPref;
import com.viyangle.study_tour.pojo.ReferencePair;
import com.viyangle.study_tour.pojo.Tag;
import com.viyangle.study_tour.service.ReferencePairService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ReferencePairServiceImpl implements ReferencePairService {

    @Autowired
    private ReferencePairMapper referencePairMapper;

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private AccountTagPrefMapper accountTagPrefMapper;

    @Autowired
    private TagMapper tagMapper;

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
    public List<ReferencePair> getPagedReferencePairsByPreference(Long accountId, Integer pageNum, Integer pageSize) {
        int page = (pageNum == null || pageNum < 1) ? 1 : pageNum;
        int size = (pageSize == null || pageSize < 1) ? 10 : pageSize;

        PreferenceContext context = resolvePreferenceContext(accountId);
        PageHelper.startPage(page, size);
        return referencePairMapper.selectByPreference(context.preferredTagNames(), context.regionCode());
    }

    @Override
    public List<ReferencePair> recommendReferencePairs(Long accountId, Integer pageNum, Integer pageSize) {
        return getPagedReferencePairsByPreference(accountId, pageNum, pageSize);
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

    private PreferenceContext resolvePreferenceContext(Long accountId) {
        String regionCode = null;
        List<String> preferredTagNames = Collections.emptyList();
        if (accountId != null) {
            Account account = accountMapper.selectById(accountId);
            if (account != null) {
                regionCode = account.getRegionCode();
            }

            List<AccountTagPref> tagPrefs = accountTagPrefMapper.selectByAccountId(accountId);
            if (tagPrefs != null && !tagPrefs.isEmpty()) {
                Set<Long> prefTagIds = tagPrefs.stream()
                        .map(AccountTagPref::getTagId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());

                if (!prefTagIds.isEmpty()) {
                    Map<Long, String> tagIdNameMap = tagMapper.selectAll().stream()
                            .collect(Collectors.toMap(Tag::getId, Tag::getName));

                    preferredTagNames = prefTagIds.stream()
                            .map(tagIdNameMap::get)
                            .filter(Objects::nonNull)
                            .filter(name -> !name.isBlank())
                            .collect(Collectors.toList());
                }
            }
        }
        return new PreferenceContext(regionCode, preferredTagNames);
    }

    private record PreferenceContext(String regionCode, List<String> preferredTagNames) {
    }
}
