package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.ReferenceRoute;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ReferenceRouteMapper {

    int insert(ReferenceRoute referenceRoute);

    int updateById(ReferenceRoute referenceRoute);

    int deleteById(@Param("id") Long id);

    ReferenceRoute selectById(@Param("id") Long id);

    List<ReferenceRoute> selectAll();

    List<ReferenceRoute> selectByPreference(@Param("preferredTags") List<String> preferredTags,
                                            @Param("regionCode") String regionCode);
}
