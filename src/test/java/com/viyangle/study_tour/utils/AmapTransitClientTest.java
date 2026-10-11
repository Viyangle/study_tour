package com.viyangle.study_tour.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.viyangle.study_tour.pojo.Attraction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AmapTransitClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private AmapTransitClient client;

    @BeforeEach
    void setUp() {
        client = new AmapTransitClient();
        ReflectionTestUtils.setField(client, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(client, "amapKey", "test-key");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void reversePoiOrderUsesTheSameCanonicalCacheKey() throws Exception {
        AmapTransitClient.TransitEdge cached = edge("POI-A", "POI-B");
        when(valueOperations.get("amap:transit:v1:POI-A:POI-B"))
                .thenReturn(MAPPER.writeValueAsString(cached));

        AmapTransitClient.TransitEdge result = client.getTransitEdge(
                attraction("poi-b"), attraction("poi-a"));

        assertEquals("POI-A", result.getFromPoiId());
        assertEquals("POI-B", result.getToPoiId());
        verify(valueOperations).get("amap:transit:v1:POI-A:POI-B");
    }

    @Test
    void matrixReadsAllCachedEdgesInOneRedisCall() throws Exception {
        List<String> keys = List.of(
                "amap:transit:v1:POI-A:POI-B",
                "amap:transit:v1:POI-B:POI-C",
                "amap:transit:v1:POI-A:POI-C"
        );
        when(valueOperations.multiGet(keys)).thenReturn(List.of(
                MAPPER.writeValueAsString(edge("POI-A", "POI-B")),
                MAPPER.writeValueAsString(edge("POI-B", "POI-C")),
                MAPPER.writeValueAsString(edge("POI-A", "POI-C"))
        ));

        List<AmapTransitClient.TransitEdge> result = client.buildUndirectedMatrix(List.of(
                attraction("POI-B"), attraction("POI-A"), attraction("POI-C")));

        assertEquals(3, result.size());
        verify(valueOperations).multiGet(keys);
    }

    private Attraction attraction(String poiId) {
        Attraction attraction = new Attraction();
        attraction.setPoiId(poiId);
        attraction.setLocation("118.0,32.0");
        attraction.setCitycode("025");
        return attraction;
    }

    private AmapTransitClient.TransitEdge edge(String fromPoiId, String toPoiId) {
        AmapTransitClient.TransitEdge edge = new AmapTransitClient.TransitEdge();
        edge.setFromPoiId(fromPoiId);
        edge.setToPoiId(toPoiId);
        edge.setBestLines(List.of());
        return edge;
    }
}
