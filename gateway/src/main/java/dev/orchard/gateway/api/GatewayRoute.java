package dev.orchard.gateway.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.orchard.core.model.ContainerRuntime;

import java.util.UUID;

/**
 * Route trellis returns for a routable grove. Mirrors trellis's GatewayGroveResponse: a {@link RouteKind#VM}
 * route sets {@code host} and {@code sshPort}; a {@link RouteKind#CONTAINER} route sets {@code runtime} and
 * {@code containerId}, with {@code user}, {@code workspaceFolder} and {@code engineUrl} optional. Unknown fields are ignored so
 * a newer trellis can add fields without breaking routing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GatewayRoute(
    UUID groveId,
    UUID cultivatorId,
    String state,
    RouteKind kind,
    String host,
    Integer sshPort,
    ContainerRuntime runtime,
    String containerId,
    String user,
    String workspaceFolder,
    String engineUrl
) {}
