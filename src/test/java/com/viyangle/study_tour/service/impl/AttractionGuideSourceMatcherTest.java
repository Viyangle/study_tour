package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.pojo.Attraction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AttractionGuideSourceMatcherTest {

    @Test
    void queryCandidatesRemoveBranchAndScenicSuffix() {
        Attraction attraction = attraction("江宁织造博物馆(南门)", "南京市", "秦淮区", null);

        assertThat(AttractionGuideSourceMatcher.queryCandidates(attraction))
                .containsExactly("江宁织造博物馆(南门)", "江宁织造博物馆");
    }

    @Test
    void queryCandidatesRemoveChinaPrefixForRenamedMuseum() {
        Attraction attraction = attraction("中国南京云锦博物馆", "南京市", "建邺区", null);

        assertThat(AttractionGuideSourceMatcher.queryCandidates(attraction))
                .contains("南京云锦博物馆");
    }

    @Test
    void administrativeOrParentEntityIsRejectedForScenicPoint() {
        Attraction attraction = attraction("栖霞山风景名胜区", "南京市", "栖霞区", null);

        assertThat(AttractionGuideSourceMatcher.isRelevant(attraction,
                "栖霞山风景名胜区", "栖霞区",
                "栖霞区位于中国江苏省南京东北部，北临长江。")).isFalse();
        assertThat(AttractionGuideSourceMatcher.isRelevant(attraction,
                "栖霞山风景名胜区", "栖霞山",
                "栖霞山位于南京市栖霞区，以秋日红叶闻名。")).isTrue();
    }

    @Test
    void memorialHallArticleIsNotAcceptedForIndividualMonument() {
        Attraction attraction = attraction("侵华日军南京大屠杀中山码头遇难同胞纪念碑",
                "南京市", "鼓楼区", "PARENT-1");

        assertThat(AttractionGuideSourceMatcher.isRelevant(attraction,
                "侵华日军南京大屠杀中山码头遇难同胞纪念碑", "侵华日军南京大屠杀遇难同胞纪念馆",
                "侵华日军南京大屠杀遇难同胞纪念馆位于南京市建邺区。")).isFalse();
    }

    @Test
    void looseAliasWithOnlySharedCharsIsRejected() {
        Attraction attraction = attraction("宝船厂遗址公园", "南京市", "鼓楼区", null);

        assertThat(AttractionGuideSourceMatcher.isRelevant(attraction,
                "宝船厂遗址公园", "龙江船厂",
                "龙江船厂位于江苏省南京市，是明朝官营船厂，与郑和下西洋有关。")).isFalse();
    }

    @Test
    void nameWithoutEntityTokenStillAcceptsExactLocalArticle() {
        Attraction attraction = attraction("乌龙潭公园", "南京市", "鼓楼区", null);

        assertThat(AttractionGuideSourceMatcher.isRelevant(attraction,
                "乌龙潭公园", "乌龙潭公园",
                "乌龙潭公园位于江苏省南京市鼓楼区清凉山东麓。")).isTrue();
    }

    @Test
    void encyclopediaSubtitleAfterCanonicalNameIsAccepted() {
        Attraction attraction = attraction("钟山文学馆", "南京市", "玄武区", "PARENT-1");

        assertThat(AttractionGuideSourceMatcher.isRelevant(attraction,
                "钟山文学馆", "钟山文学馆·刘勰与文心雕龙陈列馆",
                "钟山文学馆位于南京市玄武区明孝陵景区定林山庄。")).isTrue();
    }

    @Test
    void oneCharacterTypoIsAcceptedOnlyWithLocalEvidence() {
        Attraction attraction = attraction("拉贝旧居", "南京市", "鼓楼区", null);

        assertThat(AttractionGuideSourceMatcher.isRelevant(attraction,
                "拉贝旧居", "拉贝故居",
                "拉贝故居位于江苏省南京市鼓楼区小粉桥1号。")).isTrue();
    }

    @Test
    void renamedMuseumIsAcceptedOnlyWithLocalEvidence() {
        Attraction attraction = attraction("中国南京云锦博物馆", "南京市", "建邺区", null);

        assertThat(AttractionGuideSourceMatcher.isRelevant(attraction,
                "中国南京云锦博物馆", "南京云锦博物馆",
                "南京云锦博物馆位于南京市建邺区茶亭东街240号。")).isTrue();
    }

    @Test
    void sameNameElsewhereIsRejectedWithoutLocalEvidence() {
        Attraction attraction = attraction("和平雕像", "南京市", "建邺区", "PARENT-1");

        assertThat(AttractionGuideSourceMatcher.isRelevant(attraction,
                "和平雕像", "“慰安妇”受害者雕像",
                "该雕像位于德国首都柏林，设立于2020年。")).isFalse();
    }

    @Test
    void shortChildPoiRequiresLocalEvidenceEvenWhenTitleMatches() {
        Attraction attraction = attraction("遇难同胞纪念碑", "南京市", "玄武区", "PARENT-1");

        assertThat(AttractionGuideSourceMatcher.isRelevant(attraction,
                "遇难同胞纪念碑", "遇难同胞纪念碑",
                "该纪念碑位于某市，具体位置不详。")).isFalse();
    }

    @Test
    void primaryAttractionNeedsLocalEvidenceEvenWithExactTitle() {
        Attraction attraction = attraction("总统府", "南京市", "玄武区", null);

        assertThat(AttractionGuideSourceMatcher.isRelevant(attraction,
                "总统府", "总统府", "总统府是中国近代建筑遗存。")).isFalse();
        assertThat(AttractionGuideSourceMatcher.isRelevant(attraction,
                "总统府", "总统府", "南京总统府位于南京市玄武区，是中国近代建筑遗存。")).isTrue();
    }

    private Attraction attraction(String name, String city, String district, String parentPoiId) {
        Attraction attraction = new Attraction();
        attraction.setPoiId("POI-1");
        attraction.setName(name);
        attraction.setCityname(city);
        attraction.setAdname(district);
        attraction.setParentPoiId(parentPoiId);
        return attraction;
    }
}
