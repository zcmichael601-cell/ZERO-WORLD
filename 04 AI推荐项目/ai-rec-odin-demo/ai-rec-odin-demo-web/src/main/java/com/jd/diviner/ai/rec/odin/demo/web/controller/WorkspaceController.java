package com.jd.diviner.ai.rec.odin.demo.web.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class WorkspaceController {
    @GetMapping({"/", "/index.html"})
    public String workspace() {
        return "redirect:/skills.html";
    }
}
