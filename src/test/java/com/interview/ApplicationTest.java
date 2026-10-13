package com.interview;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"APP_PASSWORD=integration-test-password-only", "OPENAI_API_KEY=", "OPENAI_MODEL=", "SESSION_COOKIE_SECURE=false"})
@AutoConfigureMockMvc
class ApplicationTest {
    @Autowired MockMvc mvc;
    static final String INPUT="""
      {"action":"turn","config":{"role":"工程经理","resume":"","jd":"","style":"coach","type":"behavioral","language":"zh","rounds":4},"history":[]}
      """;
    @Test void healthAccessibleButAppRequiresLogin() throws Exception {
        mvc.perform(get("/healthz")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
        mvc.perform(get("/")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/api/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/csrf")).andExpect(status().isOk()).andExpect(jsonPath("$.token").isString());
    }
    @Test void passwordLoginCreatesWorkingSession() throws Exception {
        var response=mvc.perform(formLogin("/login").user("owner").password("integration-test-password-only"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/")).andReturn();
        var session=(MockHttpSession)response.getRequest().getSession(false);
        assertNotNull(session);
        mvc.perform(get("/index.html").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/status").session(session)).andExpect(jsonPath("$.configured").value(false));
    }
    @Test void csrfAndMissingKeyAreEnforced() throws Exception {
        mvc.perform(post("/api/agent").with(user("owner")).contentType("application/json").content(INPUT)).andExpect(status().isForbidden());
        mvc.perform(post("/api/agent").with(user("owner")).with(csrf()).contentType("application/json").content(INPUT))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").isString());
    }
    @Test void malformedAndOversizeBodyRejectedBeforeModelCall() throws Exception {
        mvc.perform(post("/api/agent").with(user("owner")).with(csrf()).contentType("application/json").content("{"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/agent").with(user("owner")).with(csrf()).contentType("application/json").content("x".repeat(220001)))
            .andExpect(status().isPayloadTooLarge());
    }
}
