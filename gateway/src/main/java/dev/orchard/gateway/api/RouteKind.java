package dev.orchard.gateway.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum RouteKind {
    @JsonProperty("vm") VM,
    @JsonProperty("container") CONTAINER
}
