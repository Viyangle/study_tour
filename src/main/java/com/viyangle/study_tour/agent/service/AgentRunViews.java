package com.viyangle.study_tour.agent.service;

import com.viyangle.study_tour.agent.dto.RunView;

/** 把内部任务对象转换成对外 RunView。 */
public final class AgentRunViews {

    private AgentRunViews() {
    }

    public static RunView toView(AgentRunStore.RunEntry entry) {
        RunView view = new RunView();
        view.setRunId(entry.getRunId());
        view.setStatus(entry.getStatus());
        view.setStage(entry.getStage());
        view.setEvents(entry.snapshotEvents());
        view.setResult(entry.getResult());
        view.setError(entry.getError());
        return view;
    }
}
