package com.viyangle.study_tour.agent.service;

import com.viyangle.study_tour.agent.dto.Constraints;

import java.util.ArrayList;
import java.util.List;

/**
 * 按 {@code docs/API.md} 的 Constraints 表补齐默认值。
 *
 * <p>结果中的 {@code constraints} 必须是“本轮更新后的完整约束，下一轮可以继续传入”，
 * 因此这里返回一份带全部字段的副本。</p>
 */
public final class ConstraintDefaults {

    private ConstraintDefaults() {
    }

    public static Constraints apply(Constraints source) {
        Constraints target = new Constraints();
        target.setCity(blankToEmpty(source == null ? null : source.getCity()));
        target.setDate(source == null ? null : emptyToNull(source.getDate()));
        target.setDays(source == null || source.getDays() == null ? 1 : source.getDays());
        target.setDailyMinutes(source == null || source.getDailyMinutes() == null ? 480 : source.getDailyMinutes());
        target.setStartTime(source == null || blank(source.getStartTime()) ? "09:00" : source.getStartTime());
        target.setFinishByTime(source == null ? null : emptyToNull(source.getFinishByTime()));
        target.setBudget(source == null ? null : source.getBudget());
        target.setMaxWalkingMeters(source == null ? null : source.getMaxWalkingMeters());

        List<String> modes = source == null ? null : source.getModes();
        if (modes == null || modes.isEmpty()) {
            target.setModes(new ArrayList<>(List.of("walking", "transit")));
        } else {
            target.setModes(new ArrayList<>(modes));
        }
        target.setMustVisit(source == null || source.getMustVisit() == null
                ? new ArrayList<>() : new ArrayList<>(source.getMustVisit()));
        target.setAvoid(source == null || source.getAvoid() == null
                ? new ArrayList<>() : new ArrayList<>(source.getAvoid()));
        target.setInterests(source == null || source.getInterests() == null
                ? new ArrayList<>() : new ArrayList<>(source.getInterests()));
        target.setGroup(blankToEmpty(source == null ? null : source.getGroup()));
        target.setAccessibility(blankToEmpty(source == null ? null : source.getAccessibility()));
        target.setPace(source == null || blank(source.getPace()) ? "balanced" : source.getPace());
        target.setReturnToStart(source != null && Boolean.TRUE.equals(source.getReturnToStart()));
        return target;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
