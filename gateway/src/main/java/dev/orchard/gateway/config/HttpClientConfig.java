package dev.orchard.gateway.config;

import dev.orchard.gateway.api.TrellisApiClient;
import dev.orchard.gateway.auth.FenceTokenClient;
import org.apache.sshd.client.SshClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class HttpClientConfig {

    @Bean(destroyMethod = "stop")
    SshClient sshClient() {
        return SshClient.setUpDefaultClient();
    }

    @Bean
    FenceTokenClient fenceTokenClient(RestClient.Builder builder, GatewayProperties properties) {
        return new FenceTokenClient(builder.build(), properties);
    }

    @Bean
    RestClient trellisRestClient(GatewayProperties properties, FenceTokenClient fenceTokenClient) {
        return TrellisApiClient.buildRestClient(properties.getTrellis().getBaseUrl(), fenceTokenClient);
    }

    @Bean
    TrellisApiClient trellisApiClient(RestClient trellisRestClient, ObjectMapper objectMapper) {
        return new TrellisApiClient(trellisRestClient, objectMapper);
    }
}
