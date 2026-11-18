package com.viyangle.study_tour.service;

import com.viyangle.study_tour.pojo.Attraction;

public interface AttractionGuideSyncService {

    /**
     * 同步单个景点的讲解。人工关闭 auto_update 或内容未变化时跳过。
     *
     * @return 实际写入或刷新讲解时返回 true
     */
    boolean syncGuide(Attraction attraction);

    /**
     * 批量处理缺失、景点已变化或来源到期的讲解。
     *
     * @return 实际写入或刷新讲解的数量
     */
    int backfillMissing(int limit);

    /**
     * 忽略“已是最新”判断，强制重算一批有效景点的讲解。
     * 用于启用 AI 或调整来源/匹配规则后的主动刷新；人工 auto_update=0 的内容仍会跳过。
     */
    int resyncAll(int limit);
}
