package com.viyangle.study_tour.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.pojo.AttractionGuideSource;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WikipediaAttractionGuideSourceClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void fetchMapsArticleRevisionLicenseAndUrl() throws Exception {
        HttpServer server = wikiServer("南京国防园",
                "南京国防园位于南京市鼓楼区虎踞路87号，是国防教育主题公园，1992年建成开放。",
                "https://zh.wikipedia.org/wiki/南京国防园");
        server.start();
        try {
            WikipediaAttractionGuideSourceClient client = new WikipediaAttractionGuideSourceClient(
                    endpoint(server), 5, 0);

            Optional<AttractionGuideSource> result = client.fetch(attraction(
                    "南京国防园", "118.754817,32.051368", null));

            assertThat(result).isPresent();
            assertThat(result.get().getSourceType()).isEqualTo("WIKIPEDIA");
            assertThat(result.get().getSourceName()).isEqualTo("中文维基百科");
            assertThat(result.get().getRevision()).isEqualTo("12345");
            assertThat(result.get().getLicense()).isEqualTo("CC BY-SA 4.0");
            assertThat(result.get().getSourceUrl()).contains("南京国防园");
            assertThat(result.get().getText()).contains("1992年");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fetchRejectsSameFirstNameFromAnotherCity() throws Exception {
        HttpServer server = wikiServer("“慰安妇”受害者雕像",
                "该雕像位于德国首都柏林，由当地团体设立，后来被迁移到附近艺术园区。",
                "https://zh.wikipedia.org/wiki/和平雕像");
        server.start();
        try {
            WikipediaAttractionGuideSourceClient client = new WikipediaAttractionGuideSourceClient(
                    endpoint(server), 5, 0);

            Optional<AttractionGuideSource> result = client.fetch(attraction(
                    "和平雕像", "118.742372,32.035217", "PARENT-1"));

            assertThat(result).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer wikiServer(String title, String extract, String fullUrl) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/w/api.php", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            ObjectNode root = MAPPER.createObjectNode();
            ObjectNode responseQuery = root.putObject("query");
            if (query != null && query.contains("list=search")) {
                responseQuery.putArray("search");
            } else {
                ArrayNode pages = responseQuery.putArray("pages");
                ObjectNode page = pages.addObject();
                page.put("pageid", 1);
                page.put("title", title);
                page.put("extract", extract);
                page.put("fullurl", fullUrl);
                page.putArray("coordinates").addObject()
                        .put("lat", 32.051368).put("lon", 118.754817);
                page.putArray("revisions").addObject().put("revid", 12345);
            }
            byte[] body = MAPPER.writeValueAsBytes(root);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        return server;
    }

    private static String endpoint(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/w/api.php";
    }

    private static Attraction attraction(String name, String location, String parentPoiId) {
        Attraction attraction = new Attraction();
        attraction.setPoiId("POI-1");
        attraction.setName(name);
        attraction.setCityname("南京市");
        attraction.setAdname("鼓楼区");
        attraction.setLocation(location);
        attraction.setParentPoiId(parentPoiId);
        return attraction;
    }
}
