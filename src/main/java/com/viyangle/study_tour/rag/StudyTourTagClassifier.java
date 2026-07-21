package com.viyangle.study_tour.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class StudyTourTagClassifier {

    public static final String DEFAULT_TAG = "历史人文";

    private static final Map<String, List<String>> TAG_KEYWORDS = new LinkedHashMap<>();

    static {
        TAG_KEYWORDS.put("红色教育", List.of(
                "抗日", "烈士", "革命", "中共", "共产党", "渡江", "胜利", "国防", "战犯", "反法西斯",
                "遇难", "大屠杀", "慰安", "八路军", "新四军", "纪念碑"
        ));
        TAG_KEYWORDS.put("高校参访", List.of(
                "大学", "学院", "高校", "校区", "校史", "校园"
        ));
        TAG_KEYWORDS.put("航天航空", List.of(
                "航空", "航天", "飞行", "空军", "火箭", "卫星"
        ));
        TAG_KEYWORDS.put("科技探索", List.of(
                "科技", "科学", "科普", "实验", "天文", "昆虫", "自然科学"
        ));
        TAG_KEYWORDS.put("非遗体验", List.of(
                "非遗", "云锦", "织造", "民俗", "手作", "剪纸", "匠", "工艺", "传统技艺"
        ));
        TAG_KEYWORDS.put("艺术美育", List.of(
                "美术", "艺术", "画院", "书法", "音乐", "戏剧", "剧院", "展览"
        ));
        TAG_KEYWORDS.put("博物馆研学", List.of(
                "博物馆", "博物院", "陈列馆", "展馆", "展览馆"
        ));
        TAG_KEYWORDS.put("地理地质", List.of(
                "地质", "矿物", "地貌", "地理", "岩石", "山体"
        ));
        TAG_KEYWORDS.put("自然生态", List.of(
                "湖", "山", "公园", "风景", "景区", "湿地", "植物", "动物", "昆虫", "生态", "自然"
        ));
        TAG_KEYWORDS.put("农耕劳动", List.of(
                "农", "田园", "耕读", "劳动", "农耕", "农业"
        ));
        TAG_KEYWORDS.put("职业启蒙", List.of(
                "职业", "产业", "工厂", "企业", "行业", "实践基地"
        ));
        TAG_KEYWORDS.put("英语实践", List.of(
                "英语", "外语", "国际", "使馆", "领事馆"
        ));
        TAG_KEYWORDS.put("摄影记录", List.of(
                "摄影", "取景", "观景", "风光", "地标"
        ));
        TAG_KEYWORDS.put("亲子互动", List.of(
                "亲子", "儿童", "少年", "乐园", "体验馆"
        ));
        TAG_KEYWORDS.put(DEFAULT_TAG, List.of(
                "历史", "文化", "故居", "旧址", "遗址", "古", "城墙", "宫", "府", "寺", "楼",
                "陵", "墓", "名人", "纪念馆", "总统府"
        ));
    }

    public List<String> classify(String... fields) {
        String haystack = joinFields(fields).toLowerCase(Locale.ROOT);
        Set<String> tags = new LinkedHashSet<>();

        for (Map.Entry<String, List<String>> entry : TAG_KEYWORDS.entrySet()) {
            if (containsAny(haystack, entry.getValue())) {
                tags.add(entry.getKey());
            }
        }

        if (tags.isEmpty()) {
            tags.add(DEFAULT_TAG);
        }
        return new ArrayList<>(tags);
    }

    public String primaryTag(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return DEFAULT_TAG;
        }
        return tags.get(0);
    }

    private boolean containsAny(String haystack, List<String> keywords) {
        if (haystack == null || haystack.isBlank() || keywords == null || keywords.isEmpty()) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank()
                    && haystack.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String joinFields(String... fields) {
        if (fields == null || fields.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String field : fields) {
            if (field != null && !field.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(field);
            }
        }
        return sb.toString();
    }
}
