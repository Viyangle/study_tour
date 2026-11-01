package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.AttractionTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AttractionTagMapper {

    int insert(AttractionTag attractionTag);

    int batchInsert(@Param("list") List<AttractionTag> list);

    int deleteByPoiId(@Param("poiId") String poiId);

    int deleteByPoiIdAndTagId(@Param("poiId") String poiId, @Param("tagId") Long tagId);

    List<AttractionTag> selectByPoiId(@Param("poiId") String poiId);

    List<AttractionTag> selectByTagId(@Param("tagId") Long tagId);

    List<AttractionTag> selectAll();

    /**
     * 删除指定维度上的自动权重（保留 MANUAL），用于重新跑 LLM/规则打分。
     * 只删 15 个向量维度对应的 tag，保留 SCENIC/ROUTE_STYLE 等辅助标签。
     */
    int deleteAutoWeightsByPoiIdAndTagIds(@Param("poiId") String poiId,
                                          @Param("tagIds") List<Long> tagIds);

    /**
     * 强制重算时删除指定维度上的全部来源（含 MANUAL），仅后台补标/管理员重算使用。
     */
    int deleteWeightsByPoiIdAndTagIds(@Param("poiId") String poiId,
                                      @Param("tagIds") List<Long> tagIds);

    /**
     * 写入自动生成的先验权重；调用前应先删掉同景点的自动权重。
     */
    int insertWithWeight(@Param("poiId") String poiId,
                         @Param("tagId") Long tagId,
                         @Param("source") String source,
                         @Param("priorWeight") double priorWeight,
                         @Param("priorStrength") double priorStrength);

    /**
     * 写入反馈证据：已有行只更新 pos/neg，不覆盖先验与 source；没有行时创建弱先验行。
     */
    int upsertEvidence(@Param("poiId") String poiId,
                       @Param("tagId") Long tagId,
                       @Param("posCount") double posCount,
                       @Param("negCount") double negCount);

    /**
     * 查询指定标签名称对应的景点 poiId 列表（跨表联查）。
     */
    List<String> selectPoiIdsByTagName(@Param("tagName") String tagName);

    /**
     * 查询指定标签名称列表对应的景点 poiId 列表（任一标签匹配即可）。
     */
    List<String> selectPoiIdsByTagNames(@Param("tagNames") List<String> tagNames);
}
