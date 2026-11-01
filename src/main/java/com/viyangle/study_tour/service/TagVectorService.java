package com.viyangle.study_tour.service;

import com.viyangle.study_tour.pojo.AttractionTagVector;
import com.viyangle.study_tour.pojo.TagDimension;

import java.util.Collection;
import java.util.List;

/**
 * 景点 15 维标签向量的加载与查询。
 *
 * <p>数据来自 {@code tag_dimension} 与带权的 {@code attraction_tag}；加载失败（例如
 * 尚未执行 V4 迁移）时 {@link #isReady()} 返回 false，调用方应回退到旧的标签逻辑。</p>
 */
public interface TagVectorService {

    /** 重新加载维度与带权边；启动时自动执行一次。 */
    void reload();

    /** 是否已成功加载到可用的维度与向量。 */
    boolean isReady();

    /** 当前维度版本；未加载时返回 0。 */
    int dimVersion();

    /** 当前版本的维度列表，按 ordinal 升序。 */
    List<TagDimension> dimensions();

    /** 景点的 15 维向量；未知景点返回全 0，不会返回 null。 */
    double[] vector(String poiId);

    /**
     * 把标签名列表转成意图向量：命中维置 1，不归一化（{@link #match} 会按意图总和折算）。
     */
    double[] intentVector(Collection<String> tagNames);

    /**
     * 景点对意图的匹配度：{@code Σ intent_i * s_i / Σ intent_i}，结果在 0~1。
     * 用加权平均而不是余弦，避免多标签景点因为向量更长反而被压低。
     */
    double match(String poiId, double[] intent);

    /**
     * 景点对意图的原始得分：{@code Σ intent_i * s_i}。
     * 用于候选取代「每命中一个标签 +50」：单个标签最多 +50，两个标签最多 +100，
     * 与旧逻辑同量级，同时支持部分匹配。
     */
    double score(String poiId, double[] intent);

    /** 两个景点向量的余弦相似度（0~1），供 PPR 同标签边与 MMR 使用。 */
    double similarity(String poiIdA, String poiIdB);

    /** 景点向量的对外视图；未知景点返回 null。 */
    AttractionTagVector view(String poiId);
}
