package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ChatMessageMapper {

    int insertIfSessionActive(ChatMessage chatMessage);

    int deleteById(@Param("id") Long id);

    int deleteBySessionId(@Param("sessionId") Long sessionId);

    int updateById(ChatMessage chatMessage);

    ChatMessage selectById(@Param("id") Long id);

    List<ChatMessage> selectAll();

    /** 查询指定历史页；第 1 页为最近一页，页内按发送时间升序。 */
    List<ChatMessage> selectPageBySessionId(@Param("sessionId") Long sessionId,
                                            @Param("offset") long offset,
                                            @Param("pageSize") int pageSize);
}
