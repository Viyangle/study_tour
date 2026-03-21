package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.Region;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RegionMapper {

    List<Region> selectProvinces();

    List<Region> selectChildrenByParentAdcode(@Param("parentAdcode") String parentAdcode);
}
