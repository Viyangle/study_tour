package com.viyangle.study_tour.agent.service;

import com.viyangle.study_tour.agent.dto.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class AgentDraftValidatorTest {
    @Test
    void rejectsNegativeNonFiniteAndUnknownReferences() {
        Leg leg = leg();
        leg.setCost(Double.NaN);
        assertThatThrownBy(() -> AgentDraftValidator.validateLegs(List.of(leg), Set.of("A", "B"), false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cost");
        leg.setCost(0.0);
        leg.setMinutes(-1);
        assertThatThrownBy(() -> AgentDraftValidator.validateLegs(List.of(leg), Set.of("A", "B"), false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("minutes");
        leg.setMinutes(null);
        leg.setDestination("C");
        assertThatThrownBy(() -> AgentDraftValidator.validateLegs(List.of(leg), Set.of("A", "B"), false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("终点");
    }

    @Test
    void originReferenceRequiresSubmittedLocationAndPolylineCoordinatesMustBeValid() {
        Leg leg = leg();
        leg.setOrigin("__origin__");
        assertThatThrownBy(() -> AgentDraftValidator.validateLegs(List.of(leg), Set.of("B"), false)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> AgentDraftValidator.validateLegs(List.of(leg), Set.of("B"), true)).doesNotThrowAnyException();
        leg.setPolyline(List.of(List.of(200.0, 30.0)));
        assertThatThrownBy(() -> AgentDraftValidator.validateLegs(List.of(leg), Set.of("B"), true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMalformedAndOverlappingScheduleTimes() {
        DaySummary day = new DaySummary();
        day.setDay(1);
        day.setSchedule(List.of(ScheduleItem.visit("A", "A", "25:00", "26:00", 60, "")));
        assertThatThrownBy(() -> AgentDraftValidator.validateDays(List.of(day), Set.of("A"))).isInstanceOf(IllegalArgumentException.class);
        day.setSchedule(List.of(ScheduleItem.visit("A", "A", "09:00", "10:00", 60, ""),
                ScheduleItem.visit("B", "B", "09:30", "10:30", 60, "")));
        assertThatThrownBy(() -> AgentDraftValidator.validateDays(List.of(day), Set.of("A", "B")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("重叠");
    }

    @Test
    void completionRequiresScheduleCoverageAndMatchingDayReferences() {
        AgentPlan plan = new AgentPlan();
        RouteNode a = new RouteNode(); a.setPoiId("A"); a.setRecommendedDuration(60);
        RouteNode b = new RouteNode(); b.setPoiId("B"); b.setRecommendedDuration(60);
        plan.setNodes(List.of(a, b));
        DaySummary day = new DaySummary(); day.setDay(1);
        day.setSchedule(List.of(ScheduleItem.visit("A", "A", "09:00", "10:00", 60, "")));
        plan.setDays(List.of(day));
        assertThatThrownBy(() -> AgentDraftValidator.validateComplete(plan, false)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("每个景点");
        day.setSchedule(List.of(ScheduleItem.visit("A", "A", "09:00", "10:00", 60, ""),
                ScheduleItem.visit("B", "B", "10:30", "11:30", 60, "")));
        Leg leg = leg(); leg.setDay(2);
        plan.setLegs(List.of(leg));
        assertThatThrownBy(() -> AgentDraftValidator.validateComplete(plan, false)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不存在的天数");
    }

    private static Leg leg() {
        Leg leg = new Leg();
        leg.setDay(1); leg.setOrigin("A"); leg.setDestination("B"); leg.setMode("walking");
        return leg;
    }
}
