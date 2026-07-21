package com.viyangle.study_tour.service.impl;

import com.github.pagehelper.PageHelper;
import com.viyangle.study_tour.mapper.AccountMapper;
import com.viyangle.study_tour.mapper.AccountTagPrefMapper;
import com.viyangle.study_tour.mapper.AttractionMapper;
import com.viyangle.study_tour.mapper.RouteAttractionMapper;
import com.viyangle.study_tour.mapper.RouteMapper;
import com.viyangle.study_tour.mapper.TagMapper;
import com.viyangle.study_tour.pojo.Account;
import com.viyangle.study_tour.pojo.AccountTagPref;
import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.pojo.Route;
import com.viyangle.study_tour.pojo.RouteAttraction;
import com.viyangle.study_tour.pojo.Tag;
import com.viyangle.study_tour.service.RouteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
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
public class RouteServiceImpl implements RouteService {
    private static final String AI_ROUTE_MAPPING_KEY_PREFIX = "ai:route:memory:";


    @Autowired
    private RouteMapper routeMapper;

    @Autowired
    private RouteAttractionMapper routeAttractionMapper;

    @Autowired
    private AttractionMapper attractionMapper;

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private AccountTagPrefMapper accountTagPrefMapper;

    @Autowired
    private TagMapper tagMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;
    @Transactional
    @Override
    public Long generateRouteByManual(List<RouteAttraction> routeAttractions) {
        List<RouteAttraction> normalized = normalizeManualRouteAttractions(routeAttractions);
        Route route = new Route();
        route.setCreatedAt(LocalDateTime.now());
        route.setRegionAdcode(resolveRegionAdcode(normalized));
        routeMapper.insert(route);
        upsertRouteAttractions(route.getId(), normalized);
        routeMapper.refreshOutdatedAttractionFlagById(route.getId());
        return route.getId();
    }

    @Transactional
    @Override
    public Long saveOrUpdateAIConversationRoute(String memoryId, String tag, List<RouteAttraction> routeAttractions) {
        String mappingKey = AI_ROUTE_MAPPING_KEY_PREFIX + memoryId;
        String routeIdText = redisTemplate.opsForValue().get(mappingKey);
        String regionAdcode = resolveRegionAdcode(routeAttractions);

        Long routeId = null;
        if (routeIdText != null && !routeIdText.isBlank()) {
            try {
                routeId = Long.parseLong(routeIdText);
            } catch (NumberFormatException ignored) {
                routeId = null;
            }
        }

        if (routeId == null || routeMapper.selectById(routeId) == null) {
            Route route = new Route();
            route.setCreatedAt(LocalDateTime.now());
            route.setRegionAdcode(regionAdcode);
            route.setTag(tag);
            routeMapper.insert(route);
            routeId = route.getId();
        } else {
            Route route = new Route();
            route.setId(routeId);
            route.setRegionAdcode(regionAdcode);
            route.setTag(tag);
            routeMapper.updateById(route);
        }

        routeAttractionMapper.deleteByRouteId(routeId);
        upsertRouteAttractions(routeId, routeAttractions);
        routeMapper.refreshOutdatedAttractionFlagById(routeId);
        redisTemplate.opsForValue().set(mappingKey, String.valueOf(routeId), Duration.ofDays(1));
        return routeId;
    }

    private String resolveRegionAdcode(List<RouteAttraction> routeAttractions) {
        if (routeAttractions == null || routeAttractions.isEmpty()) {
            return null;
        }

        RouteAttraction first = routeAttractions.stream()
                .filter(ra -> ra != null && ra.getPoiId() != null && !ra.getPoiId().isBlank())
                .min(Comparator.comparingInt(ra -> ra.getVisitOrder() == null ? Integer.MAX_VALUE : ra.getVisitOrder()))
                .orElse(null);

        if (first == null) {
            return null;
        }

        Attraction firstAttraction = attractionMapper.selectByPoiId(first.getPoiId());
        return firstAttraction == null ? null : firstAttraction.getAdcode();
    }

    private void upsertRouteAttractions(Long routeId, List<RouteAttraction> routeAttractions) {
        if (routeAttractions == null || routeAttractions.isEmpty()) {
            return;
        }

        List<String> poiIds = routeAttractions.stream()
                .map(RouteAttraction::getPoiId)
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

        for (RouteAttraction routeAttraction : routeAttractions) {
            routeAttraction.setRouteId(routeId);
            Attraction a = attractionMap.get(routeAttraction.getPoiId());
            if (a == null) {
                throw new IllegalArgumentException("Invalid poiId: " + routeAttraction.getPoiId());
            }
            routeAttraction.setParentPoiId(a.getParentPoiId());
            routeAttraction.setName(a.getName());
            routeAttraction.setAddress(a.getAddress());
            routeAttraction.setLocation(a.getLocation());
            routeAttraction.setPcode(a.getPcode());
            routeAttraction.setPname(a.getPname());
            routeAttraction.setCitycode(a.getCitycode());
            routeAttraction.setCityname(a.getCityname());
            routeAttraction.setAdcode(a.getAdcode());
            routeAttraction.setAdname(a.getAdname());
            routeAttraction.setType(a.getType());
            routeAttraction.setTypecode(a.getTypecode());
            routeAttraction.setDistance(a.getDistance());
            routeAttraction.setOpentimeToday(a.getOpentimeToday());
            routeAttraction.setOpentimeWeek(a.getOpentimeWeek());
            routeAttraction.setTel(a.getTel());
            routeAttraction.setAttractionCreatedAt(a.getCreatedAt());
            routeAttraction.setAttractionUpdatedAt(a.getUpdatedAt());
            routeAttractionMapper.insert(routeAttraction);
        }
    }

    private List<RouteAttraction> normalizeManualRouteAttractions(List<RouteAttraction> routeAttractions) {
        if (routeAttractions == null || routeAttractions.isEmpty()) {
            throw new IllegalArgumentException("routeAttractions cannot be empty");
        }

        List<RouteAttraction> normalized = new ArrayList<>(routeAttractions.size());
        for (RouteAttraction ra : routeAttractions) {
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

    @Override
    public List<Route> getPagedRoutesByPreference(Long accountId, Integer pageNum, Integer pageSize) {
        int page = (pageNum == null || pageNum < 1) ? 1 : pageNum;
        int size = (pageSize == null || pageSize < 1) ? 10 : pageSize;

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

        PageHelper.startPage(page, size);
        return routeMapper.selectByPreference(preferredTagNames, regionCode);
    }

    @Override
    public List<RouteAttraction> getRouteById(Long id) {
        return routeAttractionMapper.selectByRouteId(id);
    }
}
