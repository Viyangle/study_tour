package com.viyangle.study_tour.service;

import com.viyangle.study_tour.pojo.Attraction;

/**
 * 景点标签向量的自动打分：LLM 优先，失败时回退到关键词规则。
 *
 * <p>新景点通过高德同步写入后会立即调用 {@link #autoLabel(Attraction)}；
 * 历史数据或离线脚本写入的景点由启动/后台任务 {@link #backfillMissing(int)} 补齐。</p>
 */
public interface TagVectorLabelService {

    /** 自动打分总开关（app.tag-vector.auto-label-enabled）。 */
    boolean isEnabled();

    /** 对单个景点打分并落库，返回写入的维度数。 */
    int autoLabel(Attraction attraction);

    /** 找出还没有自动权重的景点，批量补齐；返回实际处理的景点数。 */
    int backfillMissing(int limit);

    /** 管理员手动重算单个景点（会覆盖旧的 LLM/规则权重，保留 MANUAL）。 */
    int relabel(String poiId);
}
