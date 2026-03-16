package com.viyangle.study_tour.config;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "app.https", name = "redirect-enabled", havingValue = "true")
public class HttpsRedirectConfig {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> httpsRedirectCustomizer(
            @Value("${app.https.http-port:8080}") int httpPort,
            @Value("${server.port:8443}") int httpsPort
    ) {
        return factory -> {
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setScheme("http");
            connector.setPort(httpPort);
            connector.setSecure(false);
            connector.setRedirectPort(httpsPort);
            factory.addAdditionalTomcatConnectors(connector);
        };
    }
}
