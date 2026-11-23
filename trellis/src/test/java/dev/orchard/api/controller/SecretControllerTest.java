package dev.orchard.api.controller;

import dev.orchard.api.service.SecretService;
import dev.orchard.core.model.Secret;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SecretController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({SecretController.class, GlobalExceptionHandler.class})
@ExtendWith(OutputCaptureExtension.class)
class SecretControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SecretService service;

    private final UUID cultivatorId = UUID.randomUUID();

    @Test
    void put_new_returns201WithMetadataOnly() throws Exception {
        Secret secret = Secret.store(cultivatorId, "API_TOKEN", "k1");
        when(service.put(eq(cultivatorId), eq("API_TOKEN"), any()))
            .thenReturn(new SecretService.Stored(secret, true));

        mockMvc.perform(put("/api/secrets/API_TOKEN")
                .requestAttr("cultivatorId", cultivatorId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"s3cret\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("API_TOKEN"))
            .andExpect(jsonPath("$.createdAt").exists())
            .andExpect(jsonPath("$.rotatedAt").exists())
            .andExpect(jsonPath("$.value").doesNotExist())
            .andExpect(content().string(not(containsString("s3cret"))));

        verify(service).put(cultivatorId, "API_TOKEN", "s3cret".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void put_existing_returns200() throws Exception {
        Secret secret = Secret.store(cultivatorId, "API_TOKEN", "k1");
        when(service.put(eq(cultivatorId), eq("API_TOKEN"), any()))
            .thenReturn(new SecretService.Stored(secret, false));

        mockMvc.perform(put("/api/secrets/API_TOKEN")
                .requestAttr("cultivatorId", cultivatorId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"s3cret\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("API_TOKEN"));
    }

    @Test
    void put_badName_returns400() throws Exception {
        mockMvc.perform(put("/api/secrets/my-token")
                .requestAttr("cultivatorId", cultivatorId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"s3cret\"}"))
            .andExpect(status().isBadRequest());

        verify(service, never()).put(any(), any(), any());
    }

    @Test
    void put_reservedName_returns400() throws Exception {
        mockMvc.perform(put("/api/secrets/LD_PRELOAD")
                .requestAttr("cultivatorId", cultivatorId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"s3cret\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(containsString("reserved name: LD_PRELOAD")));

        verify(service, never()).put(any(), any(), any());
    }

    @Test
    void put_missingValue_returns400() throws Exception {
        mockMvc.perform(put("/api/secrets/API_TOKEN")
                .requestAttr("cultivatorId", cultivatorId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());

        verify(service, never()).put(any(), any(), any());
    }

    @Test
    void put_noCultivator_returns401() throws Exception {
        mockMvc.perform(put("/api/secrets/API_TOKEN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"s3cret\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void list_returnsMetadata() throws Exception {
        when(service.list(cultivatorId)).thenReturn(List.of(
            Secret.store(cultivatorId, "A", "k1"), Secret.store(cultivatorId, "B", "k2")));

        mockMvc.perform(get("/api/secrets").requestAttr("cultivatorId", cultivatorId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].name").value("A"))
            .andExpect(jsonPath("$[0].value").doesNotExist())
            .andExpect(jsonPath("$[0].vaultKey").doesNotExist());
    }

    @Test
    void delete_returns204() throws Exception {
        when(service.delete(cultivatorId, "API_TOKEN")).thenReturn(true);

        mockMvc.perform(delete("/api/secrets/API_TOKEN").requestAttr("cultivatorId", cultivatorId))
            .andExpect(status().isNoContent());
    }

    @Test
    void delete_absent_returns404() throws Exception {
        when(service.delete(cultivatorId, "API_TOKEN")).thenReturn(false);

        mockMvc.perform(delete("/api/secrets/API_TOKEN").requestAttr("cultivatorId", cultivatorId))
            .andExpect(status().isNotFound());
    }

    @Test
    void errorBody_neverEchoesValue(CapturedOutput output) throws Exception {
        when(service.put(eq(cultivatorId), eq("API_TOKEN"), any()))
            .thenThrow(new IllegalArgumentException("NUL"));

        mockMvc.perform(put("/api/secrets/API_TOKEN")
                .requestAttr("cultivatorId", cultivatorId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"hunter2-distinct\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(not(containsString("hunter2-distinct"))));

        assertThat(output.getAll()).doesNotContain("hunter2-distinct");
    }

    @Test
    void malformedBody_neverEchoedOrLogged(CapturedOutput output) throws Exception {
        mockMvc.perform(put("/api/secrets/API_TOKEN")
                .requestAttr("cultivatorId", cultivatorId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\": hunter2DISTINCTMARKER}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(not(containsString("hunter2DISTINCTMARKER"))));

        assertThat(output.getAll()).doesNotContain("hunter2DISTINCTMARKER");
        verify(service, never()).put(any(), any(), any());
    }
}
