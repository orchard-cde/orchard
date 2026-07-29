package dev.orchard.fence.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("fence")
public class FenceProperties {

    private String issuer;

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }
}
