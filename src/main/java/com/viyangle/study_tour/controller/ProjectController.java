package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.service.ProjectService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/project")
public class ProjectController {
    @Autowired
    private ProjectService projectService;
}
