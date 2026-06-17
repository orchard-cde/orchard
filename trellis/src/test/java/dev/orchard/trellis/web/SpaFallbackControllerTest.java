package dev.orchard.trellis.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SpaFallbackControllerTest {

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SpaFallbackController()).build();
    }

    @Test
    void forwardsClientRoutedPathToIndex() throws Exception {
        mvc.perform(get("/groves/abc-123"))
            .andExpect(status().isOk())
            .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void forwardsTopLevelClientRoute() throws Exception {
        mvc.perform(get("/nursery"))
            .andExpect(status().isOk())
            .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void doesNotForwardApiPaths() throws Exception {
        // No /api controller in this slice -> 404, but crucially NOT forwarded to index.html.
        mvc.perform(get("/api/health"))
            .andExpect(status().isNotFound())
            .andExpect(forwardedUrl(null));
    }

    @Test
    void doesNotForwardAssetPaths() throws Exception {
        // Path with an extension is treated as an asset -> not forwarded (real 404 when absent).
        mvc.perform(get("/_next/static/chunks/main.js"))
            .andExpect(status().isNotFound())
            .andExpect(forwardedUrl(null));
    }
}
