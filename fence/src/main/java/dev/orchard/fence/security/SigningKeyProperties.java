package dev.orchard.fence.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("fence.signing-key")
public class SigningKeyProperties {

    private String path;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }
}
