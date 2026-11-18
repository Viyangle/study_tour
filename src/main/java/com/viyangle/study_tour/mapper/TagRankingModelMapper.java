package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.TagRankingModel;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TagRankingModelMapper {

    TagRankingModel selectActive();

    int deactivateAll();

    int insert(TagRankingModel model);

    int deleteByVersion(String modelVersion);
}
