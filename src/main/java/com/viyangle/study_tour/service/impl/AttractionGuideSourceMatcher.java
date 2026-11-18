package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.pojo.Attraction;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 景点百科检索词与同名错配校验。 */
final class AttractionGuideSourceMatcher {

    private AttractionGuideSourceMatcher() {
    }

    static List<String> queryCandidates(Attraction attraction) {
        Set<String> candidates = new LinkedHashSet<>();
        if (attraction == null || blank(attraction.getName())) {
            return List.of();
        }
        String name = attraction.getName().trim();
        candidates.add(name);

        String withoutBranch = name.replaceAll("[（(][^）)]*[）)]", "").trim();
        add(candidates, withoutBranch);

        String withoutScenicSuffix = withoutBranch
                .replaceAll("风景名胜区$", "")
                .replaceAll("景区$", "")
                .trim();
        add(candidates, withoutScenicSuffix);

        // 百度/求闻常把“中国南京云锦博物馆”归一到“南京云锦博物馆”。
        if (withoutBranch.startsWith("中国") && withoutBranch.length() > 4) {
            add(candidates, withoutBranch.substring(2));
        }

        if (!name.contains("南京") && name.length() <= 8 && attraction.getCityname() != null
                && attraction.getCityname().contains("南京") && attraction.getParentPoiId() == null) {
            add(candidates, "南京" + withoutBranch);
        }
        return new ArrayList<>(candidates);
    }

    private static final List<String> ENTITY_TOKENS = List.of(
            "纪念馆", "博物馆", "美术馆", "科技馆", "陈列馆", "文学馆", "旧址", "遗址", "故居", "陵园",
            "公园", "景区", "馆", "院", "寺", "宫", "府", "陵", "园", "山", "湖", "桥", "塔", "楼",
            "居", "碑", "址");

    static boolean isRelevant(Attraction attraction, String query, String matchedTitle, String sourceText) {
        if (attraction == null || blank(query) || blank(matchedTitle) || blank(sourceText)) {
            return false;
        }
        String normalizedQuery = normalize(query);
        String normalizedTitle = normalize(matchedTitle);
        if (normalizedQuery.isEmpty() || normalizedTitle.isEmpty()
                || !typeCompatible(query, matchedTitle)) {
            return false;
        }

        boolean exact = normalizedQuery.equals(normalizedTitle);
        boolean similar = bigramSimilarity(normalizedQuery, normalizedTitle) >= 0.50;
        // 子 POI 只能使用包含其完整名称的条目；不能用所属景区的整篇介绍冒充独立点位。
        if (attraction.getParentPoiId() != null
                && !normalizedQuery.contains(normalizedTitle)
                && !normalizedTitle.contains(normalizedQuery)) {
            return false;
        }
        // 包括同名景点在内，都必须先有本地城市/区县/地址证据，避免异地同名条目。
        if (!hasLocalEvidence(attraction, sourceText)) {
            return false;
        }
        String queryStem = normalizedQuery.replace("风景名胜区", "")
                .replace("景区", "").replace("公园", "");
        if (exact || similar || queryStem.equals(normalizedTitle)
                || editDistance(normalizedQuery, normalizedTitle) <= 1) {
            return true;
        }
        // 百科常给词条追加副标题，如“钟山文学馆·刘勰与文心雕龙陈列馆”。
        if (normalizedQuery.length() >= 4 && normalizedTitle.startsWith(normalizedQuery)) {
            return true;
        }
        // 仅允许“阅江楼 → 阅江楼（南京）”这类括号消歧标题，不做宽松子串别名。
        return normalizedTitle.startsWith(normalizedQuery + "（")
                || normalizedTitle.startsWith(normalizedQuery + "(");
    }

    private static boolean typeCompatible(String query, String title) {
        String queryCore = query.replaceAll("[（(][^）)]*[）)]", "")
                .replaceAll("(风景名胜区|景区|公园)$", "");
        String titleCore = title.replaceAll("[（(][^）)]*[）)]", "")
                .replaceAll("(风景名胜区|景区|公园)$", "");
        String token = "";
        int tokenIndex = -1;
        for (String candidate : ENTITY_TOKENS) {
            int index = queryCore.lastIndexOf(candidate);
            if (index < 0) {
                continue;
            }
            if (index > tokenIndex || index == tokenIndex && candidate.length() > token.length()) {
                token = candidate;
                tokenIndex = index;
            }
        }
        if (token.isBlank()) {
            return true;
        }
        if ("馆".equals(token)) {
            return titleCore.contains("馆") || titleCore.contains("旧址") || titleCore.contains("遗址");
        }
        return titleCore.contains(token);
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\p{P}\\p{Z}\\s·•《》〈〉“”‘’]", "").toLowerCase();
    }

    private static boolean hasLocalEvidence(Attraction attraction, String text) {
        if (contains(text, attraction.getCityname()) || contains(text, attraction.getAdname())) {
            return true;
        }
        String cityStem = attraction.getCityname() == null ? ""
                : attraction.getCityname().replaceAll("[市省]$", "");
        if (cityStem.length() >= 2 && text.contains(cityStem)) {
            return true;
        }
        String address = attraction.getAddress();
        if (!blank(address)) {
            String road = address.replaceAll("[0-9０-９号院弄栋座楼层室（）()\\-—].*$", "").trim();
            return address.length() >= 4 && text.contains(address)
                    || road.length() >= 3 && text.contains(road);
        }
        return false;
    }

    private static double bigramSimilarity(String left, String right) {
        if (left.equals(right)) {
            return 1.0;
        }
        if (left.length() < 2 || right.length() < 2) {
            return 0.0;
        }
        Set<String> leftGrams = bigrams(left);
        Set<String> rightGrams = bigrams(right);
        Set<String> intersection = new LinkedHashSet<>(leftGrams);
        intersection.retainAll(rightGrams);
        Set<String> union = new LinkedHashSet<>(leftGrams);
        union.addAll(rightGrams);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private static int editDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    private static Set<String> bigrams(String value) {
        Set<String> grams = new LinkedHashSet<>();
        for (int i = 0; i < value.length() - 1; i++) {
            grams.add(value.substring(i, i + 2));
        }
        return grams;
    }

    private static boolean contains(String text, String keyword) {
        return !blank(text) && !blank(keyword) && text.contains(keyword.trim());
    }

    private static void add(Set<String> candidates, String value) {
        if (!blank(value) && value.length() >= 2) {
            candidates.add(value);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
