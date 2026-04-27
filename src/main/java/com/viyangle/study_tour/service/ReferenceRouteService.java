package com.viyangle.study_tour.service;

import com.viyangle.study_tour.pojo.ReferenceRoute;
import com.viyangle.study_tour.pojo.ReferenceRouteAttraction;
import com.viyangle.study_tour.pojo.ReferenceRouteDetail;

import java.util.List;

public interface ReferenceRouteService {

    Long generateReferenceRouteByManual(String tag, List<ReferenceRouteAttraction> routeAttractions);

    void updateReferenceRouteByManual(Long referenceRouteId, String tag, List<ReferenceRouteAttraction> routeAttractions);

    void deleteReferenceRouteById(Long referenceRouteId);

    ReferenceRouteDetail getReferenceRouteDetailById(Long referenceRouteId);

    List<ReferenceRoute> getPagedReferenceRoutesByPreference(Long accountId, Integer pageNum, Integer pageSize);

    List<ReferenceRouteDetail> recommendReferenceRoutes(Long accountId, Integer pageNum, Integer pageSize);
}
