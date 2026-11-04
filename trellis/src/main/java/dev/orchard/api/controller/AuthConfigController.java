package dev.orchard.api.controller;

import dev.orchard.api.dto.AuthConfigResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/config")
public class AuthConfigController {

    private final String issuer;

    public AuthConfigController(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:#{null}}") String issuer) {
        this.issuer = issuer == null || issuer.isBlank() ? null : issuer;
    }

    @GetMapping
    public AuthConfigResponse config() {
        return new AuthConfigResponse(issuer);
    }
}
