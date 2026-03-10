package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.ChatSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ChatSessionMapper {

    int insert(ChatSession chatSession);

    int deleteById(@Param("id") Long id);

    int updateById(ChatSession chatSession);

    ChatSession selectById(@Param("id") Long id);

    List<ChatSession> selectAll();
}
