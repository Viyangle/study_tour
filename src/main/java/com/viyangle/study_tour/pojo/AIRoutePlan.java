package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AIRoutePlan {
    private String tag;
    /** 可选：模型给出的多标签权重，key 为 15 个标签名，value 0~1。 */
    private Map<String, Double> tagVector;
    private List<AIRouteItem> items;

    /** 兼容旧调用方/旧测试：只给主标签和 items。 */
    public AIRoutePlan(String tag, List<AIRouteItem> items) {
        this.tag = tag;
        this.items = items;
    }
}
