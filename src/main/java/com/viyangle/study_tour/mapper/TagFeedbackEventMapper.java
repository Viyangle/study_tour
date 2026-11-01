package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.TagFeedbackEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TagFeedbackEventMapper {

    /** 幂等写入；重复的 idempotency_key 返回 0。 */
    int insertIgnore(TagFeedbackEvent event);

    List<TagFeedbackEvent> selectPending(@Param("limit") int limit);

    int markApplied(@Param("id") Long id);

    int markIgnored(@Param("id") Long id, @Param("note") String note);

    int incrementAttempts(@Param("id") Long id);

    /** 单账号单景点近 N 天已应用的正负证据绝对值之和，用于单用户上限。 */
    double sumAppliedWeightByAccountAndPoiSince(@Param("accountId") Long accountId,
                                                @Param("poiId") String poiId,
                                                @Param("since") LocalDateTime since);
}
