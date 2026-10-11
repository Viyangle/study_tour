package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.mapper.AttractionMapper;
import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.rag.AmapAttractionRagDocumentLoader;
import com.viyangle.study_tour.utils.AmapTransitClient;
import dev.langchain4j.community.store.embedding.redis.RedisEmbeddingStore;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.filter.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.viyangle.study_tour.exception.ForbiddenException;
import com.viyangle.study_tour.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AttractionSyncServiceImplTest {

    @Mock
    private AmapTransitClient amapTransitClient;

    @Mock
    private AttractionMapper attractionMapper;

    @Mock
    private AmapAttractionRagDocumentLoader amapAttractionRagDocumentLoader;

    @Mock
    private RedisEmbeddingStore redisEmbeddingStore;

    @Mock
    private EmbeddingModel embeddingModel;

    @InjectMocks
    private AttractionSyncServiceImpl service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "syncVectorEnabled", true);
        ReflectionTestUtils.setField(service, "splitterEnabled", false);
        ReflectionTestUtils.setField(service, "maxSegmentSize", 1000);
        ReflectionTestUtils.setField(service, "maxOverlapSize", 20);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void reindexRejectsAnonymousAndOrdinaryUserBeforeAnyIndexOperation() {
        assertThrows(UnauthorizedException.class, service::reindexAll);
        authenticate("USER");
        assertThrows(ForbiddenException.class, service::reindexAll);
        verifyNoInteractions(redisEmbeddingStore, embeddingModel, amapAttractionRagDocumentLoader);
    }

    @Test
    void syncFromAmapFetchesUpsertsAndIndexesVector() {
        Attraction fetched = attraction("AMAP-X", "夫子庙");
        when(amapTransitClient.fetchAttraction("AMAP-X")).thenReturn(fetched);
        when(attractionMapper.upsert(any(Attraction.class))).thenReturn(1);
        when(amapAttractionRagDocumentLoader.buildDocument(any(Attraction.class)))
                .thenReturn(Document.from("poi_id=AMAP-X\n名称: 夫子庙",
                        new Metadata().put("poi_id", "AMAP-X")));
        when(embeddingModel.embedAll(anyList())).thenAnswer(invocation -> {
            List<?> segments = invocation.getArgument(0);
            return Response.from(java.util.Collections.nCopies(segments.size(),
                    Embedding.from(new float[]{1f, 2f, 3f})));
        });

        Attraction result = service.syncFromAmap("AMAP-X");

        assertEquals("AMAP-X", result.getPoiId());
        verify(attractionMapper).upsert(any(Attraction.class));
        verify(redisEmbeddingStore).removeAll(any(Filter.class));
        verify(redisEmbeddingStore).addAll(anyList(), anyList());
    }

    @Test
    void syncFromAmapThrowsWhenAmapReturnsNothing() {
        when(amapTransitClient.fetchAttraction("AMAP-X")).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.syncFromAmap("AMAP-X"));

        verify(attractionMapper, never()).upsert(any(Attraction.class));
    }

    @Test
    void reindexAllClearsAndReingestsAllDocuments() {
        authenticate("ADMIN");
        Document first = Document.from("poi_id=POI-1\n名称: 景点一",
                new Metadata().put("poi_id", "POI-1"));
        Document second = Document.from("poi_id=POI-2\n名称: 景点二",
                new Metadata().put("poi_id", "POI-2"));
        when(amapAttractionRagDocumentLoader.loadDocuments(any())).thenReturn(List.of(first, second));
        when(embeddingModel.embedAll(anyList())).thenAnswer(invocation -> {
            List<?> segments = invocation.getArgument(0);
            return Response.from(java.util.Collections.nCopies(segments.size(),
                    Embedding.from(new float[]{1f, 2f, 3f})));
        });

        int count = service.reindexAll();

        assertEquals(2, count);
        verify(redisEmbeddingStore).removeAll();
        verify(redisEmbeddingStore).addAll(anyList(), anyList());
    }

    private Attraction attraction(String poiId, String name) {
        Attraction attraction = new Attraction();
        attraction.setPoiId(poiId);
        attraction.setName(name);
        attraction.setLocation("118.7888,32.0200");
        attraction.setAdcode("320104");
        attraction.setCitycode("025");
        attraction.setStatus("ACTIVE");
        return attraction;
    }

    private void authenticate(String role) {
        var authentication = new UsernamePasswordAuthenticationToken("1", null, List.of());
        authentication.setDetails(Map.of("accountId", 1L, "role", role));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
