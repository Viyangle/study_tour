package com.viyangle.study_tour.service;

import com.viyangle.study_tour.pojo.AttractionTagVector;
import com.viyangle.study_tour.pojo.RouteAttraction;

import java.util.List;
import java.util.Map;

/**
 * 路线 15 维标签向量：由路线站点的景点向量按时长加权聚合而来。
 *
 * <p>存储到 {@code route_tag_weight}；{@code routes.tag} 保留为 argmax 主标签，
 * 用于兼容旧筛选和旧接口。</p>
 */
public interface RouteTagVectorService {

    /** 重算并落库，返回主标签名；没有可用向量时返回 null。 */
    String rebuild(Long routeId, List<RouteAttraction> stops);

    /**
     * 重算并落库，可融合模型给出的多标签权重。
     *
     * @param modelTagVector 模型输出，key 为标签名，value 0~1；可为 null
     */
    String rebuild(Long routeId, List<RouteAttraction> stops, Map<String, Double> modelTagVector);

    /** 路线向量；优先读 route_tag_weight，缺失时按站点实时聚合。 */
    double[] vector(Long routeId);

    /** 对外视图；路线不存在或没有向量时返回 null。 */
    AttractionTagVector view(Long routeId);
}
