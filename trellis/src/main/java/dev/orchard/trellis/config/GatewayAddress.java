package dev.orchard.trellis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Where cultivators reach the SSH gateway; used to build every grove's connection string. */
@ConfigurationProperties("orchard.gateway")
public record GatewayAddress(String sshHost, int sshPort) {}
