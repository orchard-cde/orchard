package dev.orchard.trellis.config;

import dev.orchard.knothole.FileSecretVault;
import dev.orchard.knothole.MasterKey;
import dev.orchard.knothole.SecretVault;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
@EnableConfigurationProperties(KnotholeConfig.KnotholeProperties.class)
public class KnotholeConfig {

    @ConfigurationProperties("orchard.knothole")
    public record KnotholeProperties(Path vaultPath, Path masterKeyPath) {}

    @Bean
    public MasterKey masterKey(KnotholeProperties properties) {
        return MasterKey.loadOrCreate(properties.masterKeyPath());
    }

    @Bean
    public SecretVault secretVault(KnotholeProperties properties, MasterKey masterKey) {
        return new FileSecretVault(properties.vaultPath(), masterKey);
    }
}
