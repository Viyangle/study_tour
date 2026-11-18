package com.viyangle.study_tour.service.impl;

import com.sun.net.httpserver.HttpServer;
import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.pojo.AttractionGuideSource;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BaiduBaikeMobileAttractionGuideSourceClientTest {

    @Test
    void fetchReadsMetaAndCanonicalUrl() throws Exception {
        HttpServer server = pageServer(200, """
                <html><head>
                <meta content="南京消防博物馆位于南京市鼓楼区北京西路1号，于2021年开馆。" name="description">
                <meta content="南京消防博物馆" property="og:title">
                <meta property="og:url" content="https://baike.baidu.com/item/南京消防博物馆/59252859">
                </head></html>
                """);
        server.start();
        try {
            BaiduBaikeMobileAttractionGuideSourceClient client = new BaiduBaikeMobileAttractionGuideSourceClient(
                    true, endpoint(server), 5, java.net.http.HttpClient.newHttpClient());

            Optional<AttractionGuideSource> result = client.fetch(attraction("南京消防博物馆"));

            assertThat(result).isPresent();
            assertThat(result.get().getSourceType()).isEqualTo("BAIDU_BAIKE_MOBILE");
            assertThat(result.get().getSourceName()).isEqualTo("百度百科（移动页）");
            assertThat(result.get().getText()).contains("北京西路1号");
            assertThat(result.get().getSourceUrl()).isEqualTo(
                    "https://baike.baidu.com/item/南京消防博物馆/59252859");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fetchRejectsPageFromAnotherCity() throws Exception {
        HttpServer server = pageServer(200, """
                <html><head>
                <meta name="description" content="该博物馆位于北京市，展示消防历史。">
                <meta property="og:title" content="南京消防博物馆">
                <meta property="og:url" content="https://baike.baidu.com/item/other/1">
                </head></html>
                """);
        server.start();
        try {
            BaiduBaikeMobileAttractionGuideSourceClient client = new BaiduBaikeMobileAttractionGuideSourceClient(
                    true, endpoint(server), 5, java.net.http.HttpClient.newHttpClient());

            assertThat(client.fetch(attraction("南京消防博物馆"))).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fetchSkipsMissingPage() throws Exception {
        HttpServer server = pageServer(404, "<html></html>");
        server.start();
        try {
            BaiduBaikeMobileAttractionGuideSourceClient client = new BaiduBaikeMobileAttractionGuideSourceClient(
                    true, endpoint(server), 5, java.net.http.HttpClient.newHttpClient());

            assertThat(client.fetch(attraction("不存在博物馆"))).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer pageServer(int status, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/item/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html;charset=UTF-8");
            exchange.sendResponseHeaders(status, status == 200 ? bytes.length : -1);
            if (status == 200) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            } else {
                exchange.close();
            }
        });
        return server;
    }

    private static String endpoint(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/item/";
    }

    private static Attraction attraction(String name) {
        Attraction attraction = new Attraction();
        attraction.setPoiId("POI-1");
        attraction.setName(name);
        attraction.setCityname("南京市");
        attraction.setAdname("鼓楼区");
        return attraction;
    }
}
