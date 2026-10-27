package dev.orchard.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties("orchard.gateway")
public class GatewayProperties {

    private int sshPort = 2222;
    private String hostKeyPath = Path.of(System.getProperty("user.home"), ".orchard", "gateway-host-key").toString();
    private String internalSshKeyPath = Path.of(System.getProperty("user.home"), ".ssh", "orchard_ed25519").toString();
    private final Fence fence = new Fence();
    private final OAuth2 oauth2 = new OAuth2();
    private final Trellis trellis = new Trellis();

    public static class Fence {
        private String issuerUri = "http://localhost:7779";
        public String getIssuerUri() { return issuerUri; }
        public void setIssuerUri(String issuerUri) { this.issuerUri = issuerUri; }
    }

    public static class OAuth2 {
        private String clientId = "orchard-gateway";
        private String clientSecret = "";
        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    }

    public static class Trellis {
        private String baseUrl = "http://localhost:8080";
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }

    public int getSshPort() { return sshPort; }
    public void setSshPort(int sshPort) { this.sshPort = sshPort; }
    public String getHostKeyPath() { return hostKeyPath; }
    public void setHostKeyPath(String hostKeyPath) { this.hostKeyPath = hostKeyPath; }
    public String getInternalSshKeyPath() { return internalSshKeyPath; }
    public void setInternalSshKeyPath(String internalSshKeyPath) { this.internalSshKeyPath = internalSshKeyPath; }
    public Fence getFence() { return fence; }
    public OAuth2 getOauth2() { return oauth2; }
    public Trellis getTrellis() { return trellis; }
}
