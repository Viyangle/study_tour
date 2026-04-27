package com.viyangle.study_tour.service.impl;

import com.github.pagehelper.PageHelper;
import com.viyangle.study_tour.exception.ResourceNotFoundException;
import com.viyangle.study_tour.mapper.AccountMapper;
import com.viyangle.study_tour.mapper.AccountTagPrefMapper;
import com.viyangle.study_tour.mapper.AttractionMapper;
import com.viyangle.study_tour.mapper.ReferenceRouteAttractionMapper;
import com.viyangle.study_tour.mapper.ReferenceRouteMapper;
import com.viyangle.study_tour.mapper.TagMapper;
import com.viyangle.study_tour.pojo.Account;
import com.viyangle.study_tour.pojo.AccountTagPref;
import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.pojo.ReferenceRoute;
import com.viyangle.study_tour.pojo.ReferenceRouteAttraction;
import com.viyangle.study_tour.pojo.ReferenceRouteDetail;
import com.viyangle.study_tour.pojo.Tag;
import com.viyangle.study_tour.service.ReferenceRouteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ReferenceRouteServiceImpl implements ReferenceRouteService {

    @Autowired
    private ReferenceRouteMapper referenceRouteMapper;

    @Autowired
    private ReferenceRouteAttractionMapper referenceRouteAttractionMapper;

    @Autowired
    private AttractionMapper attractionMapper;

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private AccountTagPrefMapper accountTagPrefMapper;

    @Autowired
    private TagMapper tagMapper;

    @Transactional
    @Override
    public Long generateReferenceRouteByManual(String tag, List<ReferenceRouteAttraction> routeAttractions) {
        List<ReferenceRouteAttraction> normalized = normalizeManualRouteAttractions(routeAttractions);
        ReferenceRoute referenceRoute = new ReferenceRoute();
        referenceRoute.setCreatedAt(LocalDateTime.now());
        referenceRoute.setRegionAdcode(resolveRegionAdcode(normalized));
        referenceRoute.setTag(tag);
        referenceRouteMapper.insert(referenceRoute);
        upsertRouteAttractions(referenceRoute.getId(), normalized);
        return referenceRoute.getId();
    }

    @Transactional
    @Override
    public void updateReferenceRouteByManual(Long referenceRouteId, String tag, List<ReferenceRouteAttraction> routeAttractions) {
        ReferenceRoute existing = referenceRouteMapper.selectById(referenceRouteId);
        if (existing == null) {
            throw new ResourceNotFoundException("reference route not found, id=" + referenceRouteId);
        }

        List<ReferenceRouteAttraction> normalized = normalizeManualRouteAttractions(routeAttractions);
        ReferenceRoute route = new ReferenceRoute();
        route.setId(referenceRouteId);
        route.setRegionAdcode(resolveRegionAdcode(normalized));
        route.setTag(tag);
        referenceRouteMapper.updateById(route);

        referenceRouteAttractionMapper.deleteByReferenceRouteId(referenceRouteId);
        upsertRouteAttractions(referenceRouteId, normalized);
    }

    @Transactional
    @Override
    public void deleteReferenceRouteById(Long referenceRouteId) {
        referenceRouteAttractionMapper.deleteByReferenceRouteId(referenceRouteId);
        referenceRouteMapper.deleteById(referenceRouteId);
    }

    @Override
    public ReferenceRouteDetail getReferenceRouteDetailById(Long referenceRouteId) {
        ReferenceRoute referenceRoute = referenceRouteMapper.selectById(referenceRouteId);
        if (referenceRoute == null) {
            throw new ResourceNotFoundException("reference route not found, id=" + referenceRouteId);
        }
        List<ReferenceRouteAttraction> routeAttractions = referenceRouteAttractionMapper.selectByReferenceRouteId(referenceRouteId);
        return new ReferenceRouteDetail(referenceRoute, routeAttractions);
    }

    @Override
    public List<ReferenceRoute> getPagedReferenceRoutesByPreference(Long accountId, Integer pageNum, Integer pageSize) {
        int page = (pageNum == null || pageNum < 1) ? 1 : pageNum;
        int size = (pageSize == null || pageSize < 1) ? 10 : pageSize;

        PreferenceContext context = resolvePreferenceContext(accountId);
        PageHelper.startPage(page, size);
        return referenceRouteMapper.selectByPreference(context.preferredTagNames(), context.regionCode());
    }

    @Override
    public List<ReferenceRouteDetail> recommendReferenceRoutes(Long accountId, Integer pageNum, Integer pageSize) {
        List<ReferenceRoute> routes = getPagedReferenceRoutesByPreference(accountId, pageNum, pageSize);
        List<ReferenceRouteDetail> details = new ArrayList<>(routes.size());
        for (ReferenceRoute route : routes) {
            List<ReferenceRouteAttraction> attractions = referenceRouteAttractionMapper.selectByReferenceRouteId(route.getId());
            details.add(new ReferenceRouteDetail(route, attractions));
        }
        return details;
    }

    private String resolveRegionAdcode(List<ReferenceRouteAttraction> routeAttractions) {
        if (routeAttractions == null || routeAttractions.isEmpty()) {
            return null;
        }

        ReferenceRouteAttraction first = routeAttractions.stream()
                .filter(ra -> ra != null && ra.getPoiId() != null && !ra.getPoiId().isBlank())
                .min(Comparator.comparingInt(ra -> ra.getVisitOrder() == null ? Integer.MAX_VALUE : ra.getVisitOrder()))
                .orElse(null);

        if (first == null) {
            return null;
        }

        Attraction firstAttraction = attractionMapper.selectByPoiId(first.getPoiId());
        return firstAttraction == null ? null : firstAttraction.getAdcode();
    }

    private void upsertRouteAttractions(Long referenceRouteId, List<ReferenceRouteAttraction> routeAttractions) {
        if (routeAttractions == null || routeAttractions.isEmpty()) {
            return;
        }

        List<String> poiIds = routeAttractions.stream()
                .map(ReferenceRouteAttraction::getPoiId)
                .filter(Objects::nonNull)
                .filter(poiId -> !poiId.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList();

        Map<String, Attraction> attractionMap = new HashMap<>();
        if (!poiIds.isEmpty()) {
            List<Attraction> attractions = attractionMapper.selectByPoiIds(poiIds);
            if (attractions != null) {
                for (Attraction attraction : attractions) {
                    if (attraction != null && attraction.getPoiId() != null) {
                        attractionMap.put(attraction.getPoiId(), attraction);
                    }
                }
            }
        }

        for (ReferenceRouteAttraction routeAttraction : routeAttractions) {
            routeAttraction.setReferenceRouteId(referenceRouteId);
            Attraction attraction = attractionMap.get(routeAttraction.getPoiId());
            if (attraction == null) {
                throw new IllegalArgumentException("Invalid poiId: " + routeAttraction.getPoiId());
            }
            routeAttraction.setParentPoiId(attraction.getParentPoiId());
            routeAttraction.setName(attraction.getName());
            routeAttraction.setAddress(attraction.getAddress());
            routeAttraction.setLocation(attraction.getLocation());
            routeAttraction.setPcode(attraction.getPcode());
            routeAttraction.setPname(attraction.getPname());
            routeAttraction.setCitycode(attraction.getCitycode());
            routeAttraction.setCityname(attraction.getCityname());
            routeAttraction.setAdcode(attraction.getAdcode());
            routeAttraction.setAdname(attraction.getAdname());
            routeAttraction.setType(attraction.getType());
            routeAttraction.setTypecode(attraction.getTypecode());
            routeAttraction.setDistance(attraction.getDistance());
            routeAttraction.setAttractionCreatedAt(attraction.getCreatedAt());
            routeAttraction.setAttractionUpdatedAt(attraction.getUpdatedAt());
            referenceRouteAttractionMapper.insert(routeAttraction);
        }
    }

    private List<ReferenceRouteAttraction> normalizeManualRouteAttractions(List<ReferenceRouteAttraction> routeAttractions) {
        if (routeAttractions == null || routeAttractions.isEmpty()) {
            throw new IllegalArgumentException("routeAttractions cannot be empty");
        }

        List<ReferenceRouteAttraction> normalized = new ArrayList<>(routeAttractions.size());
        for (ReferenceRouteAttraction ra : routeAttractions) {
            if (ra == null || ra.getPoiId() == null || ra.getPoiId().isBlank()) {
                throw new IllegalArgumentException("poiId cannot be empty");
            }
            normalized.add(ra);
        }

        normalized.sort(Comparator.comparingInt(ra -> ra.getVisitOrder() == null ? Integer.MAX_VALUE : ra.getVisitOrder()));
        for (int i = 0; i < normalized.size(); i++) {
            normalized.get(i).setVisitOrder(i + 1);
        }
        return normalized;
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
