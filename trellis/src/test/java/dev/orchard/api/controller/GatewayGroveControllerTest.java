package dev.orchard.api.controller;

import dev.orchard.api.dto.GatewayGroveResponse;
import dev.orchard.api.dto.GatewayKeyResponse;
import dev.orchard.api.service.GatewayGroveService;
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

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GatewayGroveController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GatewayGroveController.class, GlobalExceptionHandler.class})
class GatewayGroveControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GatewayGroveService service;

    @Test
    void resolveGrove_routable_returns200() throws Exception {
        UUID groveId = UUID.randomUUID();
        when(service.resolveRoute(groveId)).thenReturn(Optional.of(
            new GatewayGroveResponse(groveId, UUID.randomUUID(), "127.0.0.1", 22, "FLOURISHING")));

        mockMvc.perform(get("/api/gateway/groves/{groveId}", groveId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.groveId").value(groveId.toString()))
            .andExpect(jsonPath("$.seedlingIp").value("127.0.0.1"))
            .andExpect(jsonPath("$.seedlingPort").value(22))
            .andExpect(jsonPath("$.state").value("FLOURISHING"));
    }

    @Test
    void resolveGrove_unknown_returns404() throws Exception {
        UUID groveId = UUID.randomUUID();
        when(service.resolveRoute(groveId)).thenReturn(Optional.empty());
        when(service.exists(groveId)).thenReturn(false);

        mockMvc.perform(get("/api/gateway/groves/{groveId}", groveId))
            .andExpect(status().isNotFound());
    }

    @Test
    void resolveGrove_notReady_returns409() throws Exception {
        UUID groveId = UUID.randomUUID();
        when(service.resolveRoute(groveId)).thenReturn(Optional.empty());
        when(service.exists(groveId)).thenReturn(true);

        mockMvc.perform(get("/api/gateway/groves/{groveId}", groveId))
            .andExpect(status().isConflict());
    }

    @Test
    void listKeys_returnsKeys() throws Exception {
        UUID cultivatorId = UUID.randomUUID();
        when(service.listKeys(cultivatorId)).thenReturn(List.of(
            new GatewayKeyResponse(UUID.randomUUID(), "laptop", "ssh-ed25519 AAAA test@orchard.dev", "SHA256:abc")));

        mockMvc.perform(get("/api/gateway/cultivators/{id}/keys", cultivatorId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].name").value("laptop"))
            .andExpect(jsonPath("$[0].fingerprint").value("SHA256:abc"));
    }

    @Test
    void authorizeOwner_owner_returns200() throws Exception {
        UUID groveId = UUID.randomUUID();
        when(service.authorizeOwner(groveId, "alice@example.com")).thenReturn(Optional.of(
            new GatewayGroveResponse(groveId, UUID.randomUUID(), "127.0.0.1", 22, "FLOURISHING")));

        mockMvc.perform(post("/api/gateway/authorize-owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"groveId\":\"" + groveId + "\",\"email\":\"alice@example.com\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.groveId").value(groveId.toString()));
    }

    @Test
    void authorizeOwner_notOwner_returns403() throws Exception {
        UUID groveId = UUID.randomUUID();
        when(service.authorizeOwner(groveId, "bob@example.com")).thenReturn(Optional.empty());
        when(service.exists(groveId)).thenReturn(true);

        mockMvc.perform(post("/api/gateway/authorize-owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"groveId\":\"" + groveId + "\",\"email\":\"bob@example.com\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void authorizeOwner_unknownGrove_returns404() throws Exception {
        UUID groveId = UUID.randomUUID();
        when(service.authorizeOwner(groveId, "alice@example.com")).thenReturn(Optional.empty());
        when(service.exists(groveId)).thenReturn(false);

        mockMvc.perform(post("/api/gateway/authorize-owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"groveId\":\"" + groveId + "\",\"email\":\"alice@example.com\"}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void authorizeOwner_blankEmail_returns400() throws Exception {
        UUID groveId = UUID.randomUUID();

        mockMvc.perform(post("/api/gateway/authorize-owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"groveId\":\"" + groveId + "\",\"email\":\"\"}"))
            .andExpect(status().isBadRequest());
    }
}
