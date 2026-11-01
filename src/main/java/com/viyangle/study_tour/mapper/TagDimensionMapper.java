package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.TagDimension;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface TagDimensionMapper {

    /**
     * 所有维度定义，按 dim_version、ordinal 升序；服务层只取最新版本。
     */
    List<TagDimension> selectAll();
}
