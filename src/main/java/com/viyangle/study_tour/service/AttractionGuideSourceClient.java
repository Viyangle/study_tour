package com.viyangle.study_tour.service;

import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.pojo.AttractionGuideSource;

import java.util.Optional;

public interface AttractionGuideSourceClient {

    /**
     * 查询并校验与景点真正相关的公开资料；查不到或疑似同名错配时返回 empty。
     */
    Optional<AttractionGuideSource> fetch(Attraction attraction);
}
