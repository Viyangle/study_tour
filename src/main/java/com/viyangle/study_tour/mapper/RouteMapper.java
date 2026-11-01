package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.Route;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RouteMapper {

    int insert(Route route);

    int updateById(Route route);

    /** 路由向量重算后，把 routes.tag 更新为 argmax 主标签（兼容旧字段）。 */
    int updateTag(@Param("id") Long id, @Param("tag") String tag);

    int refreshOutdatedAttractionFlagById(@Param("id") Long id);

    int deleteById(@Param("id") Long id);

    Route selectById(@Param("id") Long id);

    List<Route> selectAll();

    List<Route> selectByPreference(@Param("preferredTags") List<String> preferredTags,
                                   @Param("regionCode") String regionCode);
}
