package com.jd.diviner.ai.rec.odin.demo;

import com.jd.diviner.ai.rec.odin.demo.web.controller.WorkspaceController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkspaceControllerTests {
    @Test
    void legacyHomeRedirectsToWorkspaceIncludingContextPath() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new WorkspaceController()).build();
        for (String path : new String[] {"/", "/index.html"}) {
            mvc.perform(get(path)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/skills.html"));
            mvc.perform(get("/demo" + path).contextPath("/demo"))
                    .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/demo/skills.html"));
        }
        mvc.perform(get("/api/rag/status")).andExpect(status().isNotFound());
    }
}
