package com.viyangle.study_tour.pojo;

public enum ProjectStatus {
    OPEN("待接单"),
    MATCHING("匹配中"),
    CONFIRMED("已接单"),
    IN_PROGRESS("进行中"),
    DONE("已完成"),
    CANCELLED("已取消");

    private final String displayName;

    ProjectStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean canBeInitialStatus() {
        return this == OPEN || this == MATCHING || this == CONFIRMED;
    }

    public boolean requiresLeader() {
        return this == CONFIRMED || this == IN_PROGRESS || this == DONE;
    }

    public boolean canTransitionTo(ProjectStatus target) {
        if (target == null) {
            return false;
        }
        if (this == target) {
            return true;
        }
        return switch (this) {
            case OPEN -> target == MATCHING || target == CONFIRMED || target == CANCELLED;
            case MATCHING -> target == CONFIRMED || target == CANCELLED;
            case CONFIRMED -> target == IN_PROGRESS || target == CANCELLED;
            case IN_PROGRESS -> target == DONE;
            case DONE, CANCELLED -> false;
        };
    }

    public static ProjectStatus from(String value) {
        if (value == null || value.isBlank()) {
            return OPEN;
        }
        try {
            return ProjectStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid project status: " + value);
        }
    }

    public static ProjectStatus nullableFrom(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return from(value);
    }

    public static String displayNameOf(String value) {
        return from(value).getDisplayName();
    }

    public void assertCanTransitionTo(ProjectStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalArgumentException("Invalid project status transition: " + name() + " -> " + target.name());
        }
    }
}
