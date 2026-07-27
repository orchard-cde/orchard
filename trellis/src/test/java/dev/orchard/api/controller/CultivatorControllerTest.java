package dev.orchard.api.controller;

import dev.orchard.api.service.CultivatorService;
import dev.orchard.core.model.Cultivator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CultivatorController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({CultivatorController.class, GlobalExceptionHandler.class})
class CultivatorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CultivatorService cultivatorService;

    private final UUID cultivatorId = UUID.randomUUID();

    @Test
    void getCurrentCultivator_withAttribute_returnsProfile() throws Exception {
        Instant now = Instant.now();
        Cultivator cultivator = new Cultivator(cultivatorId, "alice", "alice@example.com",
            "google", "google-sub-123", null, "Alice", now, now);
        when(cultivatorService.findById(cultivatorId)).thenReturn(Optional.of(cultivator));

        mockMvc.perform(get("/api/me").requestAttr("cultivatorId", cultivatorId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    void getCurrentCultivator_noAttribute_returns401() throws Exception {
        mockMvc.perform(get("/api/me"))
            .andExpect(status().isUnauthorized());
    }
}
