package dev.orchard.api.controller;

import dev.orchard.api.service.GroveService;
import dev.orchard.core.model.ContainerPlot;
import dev.orchard.core.model.ContainerRuntime;
import dev.orchard.core.model.ContainerSpec;
import dev.orchard.core.model.Grove;
import dev.orchard.core.model.PlotState;
import dev.orchard.core.model.VmPlot;
import dev.orchard.core.model.VmSpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GroveController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GroveController.class, GlobalExceptionHandler.class})
class GroveControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GroveService groveService;

    @Test
    void sshConfig_containerPlot_returns409() throws Exception {
        Grove grove = grove();
        when(groveService.getGrove(grove.id())).thenReturn(Optional.of(grove.withPlot(
            new ContainerPlot(UUID.randomUUID(), grove.id(), "docker", "c-1", PlotState.ROOTED,
                Instant.now(), Instant.now(), Path.of("/srv/groves/g1"), ContainerRuntime.PODMAN,
                new ContainerSpec(2, 4096)))));

        mockMvc.perform(get("/api/groves/{groveId}/ssh-config", grove.id()))
            .andExpect(status().isConflict());
    }

    @Test
    void sshConfig_vmPlot_returnsConfig() throws Exception {
        Grove grove = grove();
        when(groveService.getGrove(grove.id())).thenReturn(Optional.of(grove.withPlot(vmPlot(grove.id(), "10.0.0.5"))));

        mockMvc.perform(get("/api/groves/{groveId}/ssh-config", grove.id()))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("HostName 10.0.0.5")))
            .andExpect(content().string(containsString("Port 2222")));
    }

    @Test
    void sshConfig_vmPlotWithoutHost_returns503() throws Exception {
        Grove grove = grove();
        when(groveService.getGrove(grove.id())).thenReturn(Optional.of(grove.withPlot(vmPlot(grove.id(), null))));

        mockMvc.perform(get("/api/groves/{groveId}/ssh-config", grove.id()))
            .andExpect(status().isServiceUnavailable());
    }

    @Test
    void sshConfig_witheredVmPlotWithHost_returns503() throws Exception {
        Grove grove = grove();
        when(groveService.getGrove(grove.id())).thenReturn(Optional.of(
            grove.withPlot(vmPlot(grove.id(), "10.0.0.5").withState(PlotState.WITHERED))));

        mockMvc.perform(get("/api/groves/{groveId}/ssh-config", grove.id()))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("10.0.0.5"))));
    }

    @Test
    void sshConfig_nullPlot_returns503() throws Exception {
        Grove grove = grove();
        when(groveService.getGrove(grove.id())).thenReturn(Optional.of(grove));

        mockMvc.perform(get("/api/groves/{groveId}/ssh-config", grove.id()))
            .andExpect(status().isServiceUnavailable());
    }

    private static Grove grove() {
        return Grove.plant(UUID.randomUUID(), "g", "https://github.com/test/repo", "main");
    }

    private static VmPlot vmPlot(UUID groveId, String host) {
        return new VmPlot(UUID.randomUUID(), groveId, "qemu", "vm-1", PlotState.ROOTED,
            Instant.now(), Instant.now(), host, 2222, List.of(), VmSpec.small());
    }
}
