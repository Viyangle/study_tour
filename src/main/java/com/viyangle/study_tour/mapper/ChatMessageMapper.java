package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ChatMessageMapper {

    int insert(ChatMessage chatMessage);

    int deleteById(@Param("id") Long id);

    int updateById(ChatMessage chatMessage);

    ChatMessage selectById(@Param("id") Long id);

    List<ChatMessage> selectAll();
}
