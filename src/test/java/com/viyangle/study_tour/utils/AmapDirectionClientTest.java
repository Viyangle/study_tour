package com.viyangle.study_tour.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖高德 v5 路径规划响应的解析、失败回退与重复读缓存。
 * 除本地回环 HTTP 桩外不发起任何真实请求。
 */
class AmapDirectionClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String WALKING_JSON = """
            {
              "status": "1",
              "info": "OK",
              "route": {
                "paths": [
                  {
                    "distance": "680",
                    "cost": {"duration": "480"},
                    "steps": [
                      {"instruction": "向东步行 300 米", "polyline": "118.790000,32.060000;118.793000,32.060000"},
                      {"instruction": "右转步行 380 米", "polyline": "118.793000,32.060000;118.796000,32.060500"}
                    ]
                  }
                ]
              }
            }
            """;
    private static final String DRIVING_JSON = """
            {
              "status": "1",
              "info": "OK",
              "route": {
                "paths": [
                  {
                    "distance": "5200",
                    "cost": {"duration": "900", "tolls": "0"},
                    "steps": [{"instruction": "沿中山路行驶", "polyline": "118.790000,32.060000;118.800000,32.070000"}]
                  }
                ]
              }
            }
            """;
    private static final String TRANSIT_JSON = """
            {
              "status": "1",
              "info": "OK",
              "route": {
                "distance": "8100",
                "transits": [
                  {
                    "cost": "2.0",
                    "duration": "1500",
                    "distance": "8100",
                    "walking_distance": "900",
                    "segments": [
                      {
                        "walking": {"steps": [{"instruction": "步行 200 米到地铁站",
                          "polyline": "118.790000,32.060000;118.791000,32.060500"}]}
                      },
                      {
                        "bus": {"buslines": [{"name": "地铁1号线", "departure_stop": "新街口站",
                          "polyline": "118.791000,32.060500;118.800000,32.065000"}]}
                      }
                    ]
                  }
                ]
              }
            }
            """;

    private AmapDirectionClient client;

    @BeforeEach
    void setUp() {
        client = new AmapDirectionClient();
        ReflectionTestUtils.setField(client, "amapKey", "test-key");
        ReflectionTestUtils.setField(client, "timeoutSeconds", 5);
        ReflectionTestUtils.setField(client, "directionQps", 0);
        ReflectionTestUtils.setField(client, "cacheTtlMinutes", 5L);
    }

    @Test
    void walkingRouteUsesAmapDurationAndKeepsWalkingMeters() throws Exception {
        AmapDirectionClient.LegResult result = AmapDirectionClient.parse(
                AmapDirectionClient.Mode.WALKING, json(WALKING_JSON));

        assertNotNull(result);
        assertEquals(8, result.getMinutes());
        assertEquals(680, result.getDistanceMeters());
        assertEquals(680, result.getWalkingMeters());
        assertNull(result.getCost());
        assertEquals(List.of(
                List.of(118.79, 32.06), List.of(118.793, 32.06),
                List.of(118.796, 32.0605)), result.getPolyline());
        assertEquals(List.of("向东步行 300 米", "右转步行 380 米"), result.getInstructions());
        assertEquals("高德路径规划", result.getSource());
        assertEquals("查询时参考结果，不保证未来交通情况", result.getNote());
    }

    @Test
    void drivingRouteKeepsCostUnknownAndWalkingUnmeasured() throws Exception {
        AmapDirectionClient.LegResult result = AmapDirectionClient.parse(
                AmapDirectionClient.Mode.DRIVING, json(DRIVING_JSON));

        assertNotNull(result);
        assertEquals(15, result.getMinutes());
        assertEquals(5200, result.getDistanceMeters());
        // 驾车通行费不是每人行程费用估算，也不把未知步行当 0
        assertNull(result.getCost());
        assertNull(result.getWalkingMeters());
    }

    @Test
    void transitRouteKeepsFareWalkingDistanceAndLines() throws Exception {
        AmapDirectionClient.LegResult result = AmapDirectionClient.parse(
                AmapDirectionClient.Mode.TRANSIT, json(TRANSIT_JSON));

        assertNotNull(result);
        assertEquals(25, result.getMinutes());
        assertEquals(8100, result.getDistanceMeters());
        assertEquals(900, result.getWalkingMeters());
        assertEquals(2.0, result.getCost().doubleValue());
        assertTrue(result.getInstructions().contains("乘坐 地铁1号线"));
        assertEquals(3, result.getPolyline().size());
    }

    @Test
    void distanceIsUsedWhenDurationIsMissing() throws Exception {
        String body = """
                {"status":"1","route":{"paths":[{"distance":"480","steps":[]}]}}
                """;

        AmapDirectionClient.LegResult result = AmapDirectionClient.parse(
                AmapDirectionClient.Mode.WALKING, json(body));

        assertNotNull(result);
        assertEquals(480, result.getDistanceMeters());
        // 缺少耗时字段时不猜测耗时，交给调用方按距离估算
        assertNull(result.getMinutes());
    }

    @Test
    void amapErrorAndEmptyPathsAreRejected() throws Exception {
        assertNull(AmapDirectionClient.parse(AmapDirectionClient.Mode.WALKING, json("""
                {"status":"0","info":"INVALID_USER_KEY"}
                """)));
        assertNull(AmapDirectionClient.parse(AmapDirectionClient.Mode.WALKING, json("""
                {"status":"1","route":{"paths":[]}}
                """)));
        assertNull(AmapDirectionClient.parse(AmapDirectionClient.Mode.TRANSIT, json("""
                {"status":"1","route":{"transits":[]}}
                """)));
        assertNull(AmapDirectionClient.parse(AmapDirectionClient.Mode.WALKING, null));
    }

    @Test
    void routeWithoutKeyOrValidCoordinatesReturnsNull() {
        // 未配置高德 key（部署时通过 app.amap.key / AMAP_KEY 提供）时不发请求
        assertNull(new AmapDirectionClient()
                .route(AmapDirectionClient.Mode.WALKING, "118.79,32.06", "118.80,32.06", "025"));

        assertNull(client.route(null, "118.79,32.06", "118.80,32.06", "025"));
        assertNull(client.route(AmapDirectionClient.Mode.WALKING, "", "118.80,32.06", "025"));
        assertNull(client.route(AmapDirectionClient.Mode.WALKING, "118.79,32.06", "not-a-coordinate", "025"));
    }

    @Test
    void repeatedReadUsesCacheWithinOneRun() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/direction/walking", exchange -> {
            requests.incrementAndGet();
            byte[] body = WALKING_JSON.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            ReflectionTestUtils.setField(client, "walkingEndpoint",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/direction/walking");

            AmapDirectionClient.LegResult first =
                    client.route(AmapDirectionClient.Mode.WALKING, "118.79,32.06", "118.80,32.06", "025");
            AmapDirectionClient.LegResult second =
                    client.route(AmapDirectionClient.Mode.WALKING, "118.79,32.06", "118.80,32.06", "025");

            assertNotNull(first);
            assertEquals(680, second.getDistanceMeters());
            assertEquals(1, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpFailureReturnsNullWithoutCaching() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/direction/walking", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            ReflectionTestUtils.setField(client, "walkingEndpoint",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/direction/walking");

            assertNull(client.route(AmapDirectionClient.Mode.WALKING, "118.79,32.06", "118.80,32.06", "025"));
            assertNull(client.route(AmapDirectionClient.Mode.WALKING, "118.79,32.06", "118.80,32.06", "025"));
            assertEquals(2, requests.get());
        } finally {
            server.stop(0);
        }
    }

    private static JsonNode json(String body) throws Exception {
        return body == null ? null : MAPPER.readTree(body);
    }
}
