package dev.orchard.api.controller;

import dev.orchard.api.service.SshPublicKeyService;
import dev.orchard.core.model.SshPublicKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SshPublicKeyController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({SshPublicKeyController.class, GlobalExceptionHandler.class})
class SshPublicKeyControllerTest {

    private static final String KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINu3uCEBEcYqqjErEWRewbGZw8qrfWt/0inp+HfZR7MR test@orchard.dev";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SshPublicKeyService service;

    private final UUID cultivatorId = UUID.randomUUID();

    @Test
    void registerKey_returns201() throws Exception {
        SshPublicKey key = SshPublicKey.register(cultivatorId, "work-laptop", KEY);
        when(service.register(eq(cultivatorId), eq("work-laptop"), any())).thenReturn(key);

        mockMvc.perform(post("/api/ssh-keys")
                .requestAttr("cultivatorId", cultivatorId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"work-laptop\",\"publicKey\":\"" + KEY + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(key.id().toString()))
            .andExpect(jsonPath("$.name").value("work-laptop"))
            .andExpect(jsonPath("$.fingerprint").value("SHA256:5h8EgYzAsG8Fzte4Vy+j+E9CN2lHRyowxZlFhnUA2Rc"));
    }

    @Test
    void registerKey_noCultivatorAttribute_returns401() throws Exception {
        mockMvc.perform(post("/api/ssh-keys")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"work-laptop\",\"publicKey\":\"" + KEY + "\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void registerKey_blankName_returns400() throws Exception {
        mockMvc.perform(post("/api/ssh-keys")
                .requestAttr("cultivatorId", cultivatorId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\",\"publicKey\":\"" + KEY + "\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void listKeys_returnsAll() throws Exception {
        SshPublicKey key = SshPublicKey.register(cultivatorId, "work-laptop", KEY);
        when(service.listForCultivator(cultivatorId)).thenReturn(List.of(key));

        mockMvc.perform(get("/api/ssh-keys")
                .requestAttr("cultivatorId", cultivatorId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].name").value("work-laptop"));
    }

    @Test
    void listKeys_noCultivatorAttribute_returns401() throws Exception {
        mockMvc.perform(get("/api/ssh-keys"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void deleteKey_owned_returns204() throws Exception {
        UUID keyId = UUID.randomUUID();
        when(service.delete(cultivatorId, keyId)).thenReturn(true);

        mockMvc.perform(delete("/api/ssh-keys/{keyId}", keyId)
                .requestAttr("cultivatorId", cultivatorId))
            .andExpect(status().isNoContent());
    }

    @Test
    void deleteKey_notOwned_returns404() throws Exception {
        UUID keyId = UUID.randomUUID();
        when(service.delete(cultivatorId, keyId)).thenReturn(false);

        mockMvc.perform(delete("/api/ssh-keys/{keyId}", keyId)
                .requestAttr("cultivatorId", cultivatorId))
            .andExpect(status().isNotFound());
    }

    @Test
    void deleteKey_noCultivatorAttribute_returns401() throws Exception {
        mockMvc.perform(delete("/api/ssh-keys/{keyId}", UUID.randomUUID()))
            .andExpect(status().isUnauthorized());
    }
}
