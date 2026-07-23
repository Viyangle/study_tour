package com.viyangle.study_tour.service;

import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.pojo.Project;
import com.viyangle.study_tour.pojo.RecommendedProject;

import java.util.List;
import java.util.Set;

/**
 * 基于知识图谱的推荐服务接口。
 */
public interface KnowledgeGraphRecommendService {

    /**
     * 首页项目推荐：根据用户偏好，在知识图谱上打分排序，返回推荐项目列表。
     *
     * @param accountId 当前用户ID（可为null，表示游客）
     * @param limit     返回数量上限
     * @return 推荐的项目列表（按得分降序）
     */
    List<Project> recommendProjects(Long accountId, int limit);

    /**
     * AI 路线规划候选召回：根据标签和地区，从知识图谱中检索候选景点。
     * 利用标签匹配 + 相邻扩展，返回适合串成路线的景点集合。
     *
     * @param tagNames   研学标签名称列表（如 ["历史人文", "自然生态"]）
     * @param regionCode 地区adcode（可为null）
     * @param limit      返回景点数量上限
     * @return 候选景点列表
     */
    List<Attraction> retrieveCandidatesByGraph(List<String> tagNames, String regionCode, int limit);

    /**
     * 返回带解释的推荐结果。
     * 解释路径示例："因为你偏好[历史人文]，该项目路线包含[故宫]等历史景点"
     *
     * @param accountId 当前用户ID（可为null）
     * @param limit     返回数量上限
     * @return 带解释的推荐项目列表
     */
    List<RecommendedProject> recommendWithExplanation(Long accountId, int limit);
}
