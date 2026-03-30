package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.Project;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ProjectMapper {

    int insert(Project project);

    int deleteById(@Param("id") Long id);

    int updateById(Project project);

    Project selectById(@Param("id") Long id);

    List<Project> selectAll();

    List<Project> selectByPreference(@Param("preferredTags") List<String> preferredTags,
                                     @Param("regionCode") String regionCode);
}
