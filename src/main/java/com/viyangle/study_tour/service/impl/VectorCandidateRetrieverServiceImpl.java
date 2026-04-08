package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.pojo.VectorRetrievalResult;
import com.viyangle.study_tour.service.VectorCandidateRetrieverService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class VectorCandidateRetrieverServiceImpl implements VectorCandidateRetrieverService {

    private static final Pattern POI_PATTERN = Pattern.compile(
            "(?i)\"?(?:poi_id|poiId|POI|from_poi_id|to_poi_id|fromPoiId|toPoiId)\"?\\s*[:=]\\s*\"?([A-Z0-9]{6,20})\"?"
    );

    @Autowired
    private ContentRetriever contentRetriever;

    @Override
    public List<String> retrieveCandidatePoiIds(String message, int limit) {
        VectorRetrievalResult retrievalResult = retrieveCandidatesWithTexts(message, limit, 0);
        return retrievalResult.getPoiIds();
    }

    @Override
    public VectorRetrievalResult retrieveCandidatesWithTexts(String message, int poiLimit, int textLimit) {
        if (message == null || message.isBlank()) {
            return new VectorRetrievalResult(List.of(), List.of());
        }

        List<Content> contents = contentRetriever.retrieve(Query.from(message));
        if (contents == null || contents.isEmpty()) {
            return new VectorRetrievalResult(List.of(), List.of());
        }

        LinkedHashSet<String> poiIds = new LinkedHashSet<>();
        List<String> retrievedTexts = new ArrayList<>();
        for (Content content : contents) {
            if (content == null || content.textSegment() == null) {
                continue;
            }
            TextSegment segment = content.textSegment();
            extractPoiIdsFromMetadata(segment.metadata(), poiIds);
            extractPoiIdsFromText(segment.text(), poiIds);
            addRetrievedText(segment, retrievedTexts);
        }

        int maxPoiSize = poiLimit <= 0 ? poiIds.size() : poiLimit;
        List<String> poiResult = new ArrayList<>(Math.min(maxPoiSize, poiIds.size()));
        for (String poiId : poiIds) {
            poiResult.add(poiId);
            if (poiResult.size() >= maxPoiSize) {
                break;
            }
        }

        int maxTextSize = textLimit <= 0 ? retrievedTexts.size() : textLimit;
        List<String> textResult = new ArrayList<>(Math.min(maxTextSize, retrievedTexts.size()));
        for (String text : retrievedTexts) {
            textResult.add(text);
            if (textResult.size() >= maxTextSize) {
                break;
            }
        }

        return new VectorRetrievalResult(poiResult, textResult);
    }

    private void extractPoiIdsFromMetadata(Metadata metadata, LinkedHashSet<String> output) {
        if (metadata == null) {
            return;
        }
        addIfPoiId(metadata.getString("poi_id"), output);
        addIfPoiId(metadata.getString("poiId"), output);
        addIfPoiId(metadata.getString("POI"), output);
        addIfPoiId(metadata.getString("from_poi_id"), output);
        addIfPoiId(metadata.getString("to_poi_id"), output);
        addIfPoiId(metadata.getString("fromPoiId"), output);
        addIfPoiId(metadata.getString("toPoiId"), output);
        addIfPoiId(metadata.getString("id"), output);
    }

    private void extractPoiIdsFromText(String text, LinkedHashSet<String> output) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher matcher = POI_PATTERN.matcher(text);
        while (matcher.find()) {
            addIfPoiId(matcher.group(1), output);
        }
    }

    private void addIfPoiId(String value, LinkedHashSet<String> output) {
        if (value == null) {
            return;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.isBlank()) {
            output.add(normalized);
        }
    }

    private void addRetrievedText(TextSegment segment, List<String> output) {
        String text = segment.text();
        if (text == null || text.isBlank()) {
            return;
        }
        String normalizedText = text.trim();
        if (!normalizedText.isBlank()) {
            output.add(normalizedText);
        }
    }
}
