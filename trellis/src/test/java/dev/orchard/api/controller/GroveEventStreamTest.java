package dev.orchard.api.controller;

import dev.orchard.api.event.GroveStateChangedEvent;
import dev.orchard.core.model.GroveState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

@WebMvcTest(GroveEventController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GroveEventController.class, GroveSseRegistry.class})
class GroveEventStreamTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private GroveSseRegistry sseRegistry;

    @Test
    void groveStateChange_reachesAnOpenSubscriberOfThatGrove() throws Exception {
        UUID groveId = UUID.randomUUID();

        MvcResult stream = mockMvc.perform(get("/api/groves/{groveId}/events", groveId))
            .andExpect(request().asyncStarted())
            .andReturn();

        eventPublisher.publishEvent(new GroveStateChangedEvent(
            groveId, UUID.randomUUID(), "my-grove",
            GroveState.PLANTING, GroveState.GROWING, Instant.parse("2026-09-21T12:34:56Z")));

        String body = stream.getResponse().getContentAsString();
        assertThat(body)
            .contains("event:grove-state-changed")
            .contains("my-grove")
            .contains("GROWING");
    }

    @Test
    void groveStateChange_doesNotReachSubscribersOfOtherGroves() throws Exception {
        UUID watched = UUID.randomUUID();

        MvcResult stream = mockMvc.perform(get("/api/groves/{groveId}/events", watched))
            .andExpect(request().asyncStarted())
            .andReturn();

        eventPublisher.publishEvent(new GroveStateChangedEvent(
            UUID.randomUUID(), UUID.randomUUID(), "someone-elses-grove",
            GroveState.PLANTING, GroveState.GROWING, Instant.parse("2026-09-21T12:34:56Z")));

        assertThat(stream.getResponse().getContentAsString()).doesNotContain("someone-elses-grove");
    }

    @Test
    void subscribing_registersTheEmitterOnTheSharedRegistry() throws Exception {
        UUID groveId = UUID.randomUUID();

        mockMvc.perform(get("/api/groves/{groveId}/events", groveId))
            .andExpect(request().asyncStarted())
            .andReturn();

        assertThat(sseRegistry.subscriberCount(groveId)).isEqualTo(1);
    }
}
