package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.ProjectMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ProjectMemberMapper {

    int insert(ProjectMember projectMember);

    int deleteById(@Param("id") Long id);

    int updateById(ProjectMember projectMember);

    ProjectMember selectById(@Param("id") Long id);

    List<ProjectMember> selectAll();
}
