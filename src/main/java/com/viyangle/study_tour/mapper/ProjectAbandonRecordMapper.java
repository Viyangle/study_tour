package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.ProjectAbandonRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ProjectAbandonRecordMapper {

    int insert(ProjectAbandonRecord record);

    List<ProjectAbandonRecord> selectByProjectId(@Param("projectId") Long projectId);
}
