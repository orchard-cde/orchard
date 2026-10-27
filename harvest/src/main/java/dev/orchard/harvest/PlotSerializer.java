package dev.orchard.harvest;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import dev.orchard.core.model.Plot;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Serializes a {@link Plot} to and from the JSON stored on a grove row. */
public class PlotSerializer {

    private static final ObjectMapper objectMapper = JsonMapper.builder()
        .changeDefaultVisibility(vc -> vc.withFieldVisibility(JsonAutoDetect.Visibility.ANY))
        .build();

    public String serialize(Plot plot) {
        return objectMapper.writeValueAsString(plot);
    }

    public Plot deserialize(String json) {
        return objectMapper.readValue(json, Plot.class);
    }
}
