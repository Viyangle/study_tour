package com.viyangle.study_tour.utils;

import com.viyangle.study_tour.pojo.AIRoutePlan;
import com.viyangle.study_tour.pojo.AIRouteItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiRoutePlanTagsTest {

    private static final Set<String> ALLOWED = Set.of("历史人文", "科技探索", "博物馆研学");

    @Test
    @DisplayName("有 tagVector 时主标签取权重最高项，并回写 tag")
    void vectorOverridesPrimaryTag() {
        AIRoutePlan plan = new AIRoutePlan();
        plan.setTag("历史人文");
        Map<String, Double> vector = new LinkedHashMap<>();
        vector.put("历史人文", 0.6);
        vector.put("科技探索", 0.9);
        plan.setTagVector(vector);
        plan.setItems(List.of(new AIRouteItem()));

        AiRoutePlanTags.normalize(plan, ALLOWED);

        assertEquals("科技探索", plan.getTag());
        assertEquals(2, plan.getTagVector().size());
    }

    @Test
    @DisplayName("非法标签和非法权重被丢弃；全部非法时回退原 tag")
    void invalidVectorEntriesAreDropped() {
        AIRoutePlan plan = new AIRoutePlan();
        plan.setTag("历史人文");
        Map<String, Double> vector = new LinkedHashMap<>();
        vector.put("不存在的标签", 1.0);
        vector.put("科技探索", 0.0);
        vector.put("博物馆研学", 1.5);
        plan.setTagVector(vector);

        AiRoutePlanTags.normalize(plan, ALLOWED);

        assertEquals("博物馆研学", plan.getTag());
        assertEquals(1.0, plan.getTagVector().get("博物馆研学"), 1e-9);
        assertEquals(1, plan.getTagVector().size());
    }

    @Test
    @DisplayName("没有 tagVector 时保持旧行为")
    void emptyVectorKeepsTag() {
        AIRoutePlan plan = new AIRoutePlan("历史人文", List.of());

        AiRoutePlanTags.normalize(plan, ALLOWED);

        assertEquals("历史人文", plan.getTag());
    }

    @Test
    @DisplayName("tag 非法且没有可用向量时抛错")
    void invalidTagWithoutVectorThrows() {
        AIRoutePlan plan = new AIRoutePlan("不存在的标签", List.of());

        assertThrows(IllegalArgumentException.class,
                () -> AiRoutePlanTags.normalize(plan, ALLOWED));
    }

    @Test
    @DisplayName("空 plan 抛错")
    void nullPlanThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> AiRoutePlanTags.normalize(null, ALLOWED));
    }
}
