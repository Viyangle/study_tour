package com.viyangle.study_tour.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 从代码生成 OpenAPI 契约，替代手工维护的接口说明。
 *
 * <p>访问 {@code /v3/api-docs} 获取 JSON，{@code /swagger-ui.html} 在线调试。
 * 需要鉴权的接口在 Swagger UI 右上角填入 access token 即可。</p>
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI studyTourOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Study Tour API")
                        .version("0.0.4")
                        .description("研学路线规划与组队平台后端接口。普通接口统一返回 {code,msg,data}；"
                                + "/routes/agent/** 与 /health 返回裸 JSON。"))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("登录接口返回的 access token")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME));
    }
}
