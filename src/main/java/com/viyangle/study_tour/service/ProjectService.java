package com.viyangle.study_tour.service;

import com.viyangle.study_tour.pojo.Project;
import com.viyangle.study_tour.pojo.ProjectAbandonRecord;
import com.viyangle.study_tour.pojo.ProjectMember;
import com.viyangle.study_tour.pojo.PageResponse;

import java.time.LocalDate;
import java.util.List;

public interface ProjectService {
    Long createProject(Project project);

    List<Project> getAllProjects();

    List<Project> getPagedProjectsByPreference(Long accountId, Integer pageNum, Integer pageSize);

    List<Project> getPagedProjectsByPreference(Long accountId,
                                               Integer pageNum,
                                               Integer pageSize,
                                               String keyword);

    PageResponse<Project> getAvailableProjectPage(Long accountId, Integer pageNum, Integer pageSize);

    /**
     * 我的项目列表。
     *
     * <p>{@code stage} 支持 {@code ACCEPTED}（CONFIRMED/IN_PROGRESS）与 {@code COMPLETED}（DONE），
     * {@code statuses} 支持直接给原始状态集合；两者非法都会抛 {@link IllegalArgumentException}（映射为 400），
     * 不会被静默忽略。三者同时出现时按 stage、statuses、status 的顺序取第一个非空条件。</p>
     */
    PageResponse<Project> getMyProjects(Long accountId,
                                        String relation,
                                        String status,
                                        String stage,
                                        List<String> statuses,
                                        Integer pageNum,
                                        Integer pageSize);

    List<Project> filterProjects(Long accountId,
                                 Integer pageNum,
                                 Integer pageSize,
                                 String keyword,
                                 String regionCode,
                                 String tag,
                                 String status,
                                 LocalDate departureDateFrom,
                                 LocalDate departureDateTo,
                                 Long ownerAccountId,
                                 Long leaderAccountId,
                                 Boolean hasLeader,
                                 Boolean onlyAvailable);

    void joinProject(Long id, Long accountId, Integer representedCount);

    void quitProject(Long id, Long accountId);

    Project getProjectDetail(Long accountId, Long id);

    List<ProjectMember> getProjectMembers(Long id);

    void acceptProject(Long id, Long leaderAccountId);

    /**
     * 领队放弃带队并重新开放项目。
     *
     * <p>要求：距出发时间至少 6 小时（按服务器时间，恰好 6 小时允许）；{@code reason} 必填，
     * 去除首尾空格后 5-200 字；项目的出发日期或时间缺失时拒绝操作。
     * 时间校验、审计记录、状态恢复与群聊领队移除在同一事务中完成。</p>
     */
    void abandonProject(Long id, Long leaderAccountId, String reason);

    /**
     * 查询项目的放弃带队审计记录。
     *
     * <p>仅项目发布者、当前或曾经的接单领队、ADMIN 可查看。</p>
     */
    List<ProjectAbandonRecord> getAbandonRecords(Long projectId, Long accountId, String role);

    void leaderJoinProject(Project project, Long currentAccountId);

    void transitionProjectStatus(Long id, String targetStatus, Long currentAccountId, String currentRole);
}
