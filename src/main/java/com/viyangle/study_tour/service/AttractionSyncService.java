package com.viyangle.study_tour.service;

import com.viyangle.study_tour.pojo.Attraction;

public interface AttractionSyncService {

    /**
     * 按 poiId 从高德 place/detail 拉取景点信息，写入 MySQL（upsert），
     * 并增量更新 Redis 向量索引，一条龙完成。
     *
     * @return 同步后的景点
     */
    Attraction syncFromAmap(String poiId);

    /**
     * 全量重建 Redis 向量索引（先清空再按数据库当前有效景点重建）。
     * 适合在手动/定期同步数据库之后调用。
     *
     * @return 写入向量库的文档数
     */
    int reindexAll();
}
