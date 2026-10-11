package com.viyangle.study_tour.agent.controller;

import com.viyangle.study_tour.agent.dto.Health;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Agent 服务健康检查，无需登录，直接返回 JSON（不包裹 Result）。 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Health health() {
        return new Health("ok", "1.0");
    }
}
