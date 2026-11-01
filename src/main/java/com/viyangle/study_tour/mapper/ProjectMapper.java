package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.Project;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Mapper
public interface ProjectMapper {

    int insert(Project project);

    int deleteById(@Param("id") Long id);

    int updateById(Project project);

    Project selectById(@Param("id") Long id);

    Project selectByIdForUpdate(@Param("id") Long id);

    int refreshCurrentMembersById(@Param("id") Long id);

    int countByLeaderAccountId(@Param("leaderAccountId") Long leaderAccountId);

    int countByLeaderAccountIdAndStatus(@Param("leaderAccountId") Long leaderAccountId,
                                        @Param("status") String status);

    List<Project> selectAll();

    List<Project> selectByPreference(@Param("preferredTags") List<String> preferredTags,
                                     @Param("regionCode") String regionCode);

    /** 软排序：按路线向量在偏好标签上的权重和排序，而不是只看 projects.tag 主标签。 */
    List<Project> selectByPreferenceSoft(@Param("preferredTags") List<String> preferredTags,
                                         @Param("regionCode") String regionCode,
                                         @Param("minTagWeight") Double minTagWeight);

    /** 软筛选 + 软排序：路线向量命中任意筛选标签且权重 ≥ minTagWeight。 */
    List<Project> selectByCompositeFilterSoft(@Param("preferredTags") List<String> preferredTags,
                                              @Param("filterTags") List<String> filterTags,
                                              @Param("sortRegionCode") String sortRegionCode,
                                              @Param("minTagWeight") Double minTagWeight,
                                              @Param("keyword") String keyword,
                                              @Param("filterRegionCode") String filterRegionCode,
                                              @Param("status") String status,
                                              @Param("departureDateFrom") LocalDate departureDateFrom,
                                              @Param("departureDateTo") LocalDate departureDateTo,
                                              @Param("ownerAccountId") Long ownerAccountId,
                                              @Param("leaderAccountId") Long leaderAccountId,
                                              @Param("hasLeader") Boolean hasLeader,
                                              @Param("onlyAvailable") Boolean onlyAvailable);

    List<Project> selectAvailableForLeader(@Param("preferredTags") List<String> preferredTags,
                                           @Param("regionCode") String regionCode,
                                           @Param("departureDate") LocalDate departureDate,
                                           @Param("departureTime") LocalTime departureTime);

    List<Project> selectByAccountRelation(@Param("accountId") Long accountId,
                                          @Param("relation") String relation,
                                          @Param("statuses") List<String> statuses);

    List<Project> selectByCompositeFilter(@Param("preferredTags") List<String> preferredTags,
                                          @Param("sortRegionCode") String sortRegionCode,
                                          @Param("keyword") String keyword,
                                          @Param("filterRegionCode") String filterRegionCode,
                                          @Param("filterTag") String filterTag,
                                          @Param("status") String status,
                                          @Param("departureDateFrom") LocalDate departureDateFrom,
                                          @Param("departureDateTo") LocalDate departureDateTo,
                                          @Param("ownerAccountId") Long ownerAccountId,
                                          @Param("leaderAccountId") Long leaderAccountId,
                                          @Param("hasLeader") Boolean hasLeader,
                                          @Param("onlyAvailable") Boolean onlyAvailable);

    int casAcceptProject(@Param("id") Long id, @Param("leaderAccountId") Long leaderAccountId);

    int casAbandonProject(@Param("id") Long id, @Param("leaderAccountId") Long leaderAccountId);

    int casTransitionStatus(@Param("id") Long id,
                            @Param("expectedStatus") String expectedStatus,
                            @Param("targetStatus") String targetStatus);

    int casIncrementCurrentMembers(@Param("id") Long id,
                                   @Param("increment") int increment);
}
