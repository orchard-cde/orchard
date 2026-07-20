package dev.orchard.api.controller;

import dev.orchard.api.service.BeeService;
import dev.orchard.core.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BeeController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({BeeController.class, GlobalExceptionHandler.class})
class BeeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BeeService beeService;

    private final UUID groveId = UUID.randomUUID();
    private final UUID beeId = UUID.randomUUID();
    private final UUID cultivatorId = UUID.randomUUID();

    @Test
    void createBee_returns202() throws Exception {
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.CLAUDE_CODE));
        when(beeService.attachBee(eq(groveId), any(), any())).thenReturn(bee);

        mockMvc.perform(post("/api/groves/{groveId}/bees", groveId)
                .header("X-Cultivator-Id", cultivatorId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"beeType\":\"CLAUDE_CODE\"}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.state").value("HATCHING"))
            .andExpect(jsonPath("$.type").value("CLAUDE_CODE"));
    }

    @Test
    void createBee_groveNotFlourishing_returns409() throws Exception {
        when(beeService.attachBee(eq(groveId), any(), any()))
            .thenThrow(new IllegalStateException("Grove must be FLOURISHING"));

        mockMvc.perform(post("/api/groves/{groveId}/bees", groveId)
                .header("X-Cultivator-Id", cultivatorId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"beeType\":\"CLAUDE_CODE\"}"))
            .andExpect(status().isConflict());
    }

    @Test
    void createBee_unregisteredBeeType_returns400() throws Exception {
        when(beeService.attachBee(eq(groveId), any(), any()))
            .thenThrow(new IllegalArgumentException("No BeeKeeper registered for bee type: CLAUDE_CODE"));

        mockMvc.perform(post("/api/groves/{groveId}/bees", groveId)
                .header("X-Cultivator-Id", cultivatorId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"beeType\":\"CLAUDE_CODE\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void listBees_returnsAll() throws Exception {
        Bee bee1 = Bee.hatching(groveId, BeeSpec.of(BeeType.CLAUDE_CODE));
        Bee bee2 = Bee.hatching(groveId, BeeSpec.of(BeeType.GEMINI));
        when(beeService.listBees(groveId)).thenReturn(List.of(bee1, bee2));

        mockMvc.perform(get("/api/groves/{groveId}/bees", groveId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void getBee_found() throws Exception {
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.CLAUDE_CODE));
        when(beeService.getBee(beeId)).thenReturn(Optional.of(bee));

        mockMvc.perform(get("/api/groves/{groveId}/bees/{beeId}", groveId, beeId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("HATCHING"));
    }

    @Test
    void getBee_notFound_returns404() throws Exception {
        when(beeService.getBee(beeId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/groves/{groveId}/bees/{beeId}", groveId, beeId))
            .andExpect(status().isNotFound());
    }

    @Test
    void wakeBee_found() throws Exception {
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.CLAUDE_CODE))
            .withState(BeeState.BUZZING);
        when(beeService.wake(beeId)).thenReturn(Optional.of(bee));

        mockMvc.perform(post("/api/groves/{groveId}/bees/{beeId}/actions/wake", groveId, beeId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("BUZZING"));
    }

    @Test
    void smokeBee_found() throws Exception {
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.CLAUDE_CODE))
            .withState(BeeState.SMOKED);
        when(beeService.smoke(beeId)).thenReturn(Optional.of(bee));

        mockMvc.perform(post("/api/groves/{groveId}/bees/{beeId}/actions/smoke", groveId, beeId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("SMOKED"));
    }
}
