package com.viyangle.study_tour.graph;

import com.viyangle.study_tour.utils.AttractionAdjacencyCalculator;
import com.viyangle.study_tour.utils.AttractionTagBatchLabeler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * 启动时自动检测知识图谱数据完整性。
 *
 * 检测逻辑：
 * 1. 如果 attractions 表有景点，但 attraction_tag 表有景点没打标签 → 自动跑 LLM 打标签
 * 2. 如果 attractions 表有景点，但 attraction_adjacency 表有景点没算相邻关系 → 自动跑相邻计算
 *
 * 前提条件：
 * - 对应的 API Key 必须配置（OPENAI_API_KEY / AMAP_KEY）
 * - 处理在后台线程执行，不阻塞应用启动
 * - 处理完成后自动重新加载知识图谱
 */
@Slf4j
@Component
public class KnowledgeGraphAutoInitializer {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private KnowledgeGraph knowledgeGraph;

    @Value("${langchain4j.open-ai.chat-model.api-key:}")
    private String openaiApiKey;

    @Value("${app.amap.key:}")
    private String amapKey;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        Thread thread = new Thread(this::checkAndProcess, "kg-auto-init");
        thread.setDaemon(true);
        thread.start();
    }

    private void checkAndProcess() {
        try {
            // 等 KnowledgeGraph 初始加载完成
            Thread.sleep(2000);

            try (Connection conn = dataSource.getConnection()) {
                int totalAttractions = knowledgeGraph.getAllAttractionPoiIds().size();
                if (totalAttractions == 0) {
                    log.info("知识图谱自动检测: 无景点数据，跳过");
                    return;
                }

                // 检测是否需要打标签
                boolean tagProcessed = false;
                try {
                    int untagged = AttractionTagBatchLabeler.countUntagged(conn);
                    if (untagged > 0) {
                        if (openaiApiKey == null || openaiApiKey.isBlank()) {
                            log.warn("知识图谱自动检测: {} 个景点未打标签，但 OPENAI_API_KEY 未配置，跳过自动打标", untagged);
                        } else {
                            log.info("知识图谱自动检测: {} 个景点未打标签，开始自动执行 LLM 打标...", untagged);
                            AttractionTagBatchLabeler.processAll(conn, openaiApiKey);
                            tagProcessed = true;
                        }
                    } else {
                        log.info("知识图谱自动检测: 所有景点已有标签");
                    }
                } catch (Exception e) {
                    log.error("知识图谱自动检测: LLM 打标失败: {}", e.getMessage(), e);
                }

                // 检测是否需要算相邻关系
                boolean adjacencyProcessed = false;
                try {
                    int withoutAdj = AttractionAdjacencyCalculator.countWithoutAdjacency(conn);
                    if (withoutAdj > 0) {
                        if (amapKey == null || amapKey.isBlank()) {
                            log.warn("知识图谱自动检测: {} 个景点未算相邻关系，但 AMAP_KEY 未配置，跳过自动计算", withoutAdj);
                        } else {
                            log.info("知识图谱自动检测: {} 个景点未算相邻关系，开始自动执行相邻计算...", withoutAdj);
                            AttractionAdjacencyCalculator.processAll(conn, amapKey);
                            adjacencyProcessed = true;
                        }
                    } else {
                        log.info("知识图谱自动检测: 所有景点已有相邻关系");
                    }
                } catch (Exception e) {
                    log.error("知识图谱自动检测: 相邻关系计算失败: {}", e.getMessage(), e);
                }

                // 如果有数据更新，重新加载知识图谱
                if (tagProcessed || adjacencyProcessed) {
                    log.info("知识图谱自动检测: 数据已更新，重新加载知识图谱...");
                    knowledgeGraph.reload();
                    log.info("知识图谱自动检测: 知识图谱重新加载完成");
                }
            }
        } catch (Exception e) {
            log.error("知识图谱自动检测异常: {}", e.getMessage(), e);
        }
    }
}
