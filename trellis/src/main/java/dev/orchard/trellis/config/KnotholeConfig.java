package dev.orchard.trellis.config;

import dev.orchard.knothole.FileSecretVault;
import dev.orchard.knothole.MasterKey;
import dev.orchard.knothole.SecretVault;
import dev.orchard.knothole.VaultSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(KnotholeConfig.KnotholeProperties.class)
public class KnotholeConfig {

    /**
     * @param vaultPath directory holding the encrypted entries
     * @param masterKeyPath file holding the vault's master key
     * @param maxValueSize largest secret value accepted
     * @param staleTempAge age after which an abandoned temp file in the vault is purged
     * @param inspectTimeout limit on inspecting a container before delivering secrets to it
     * @param deliveryTimeout limit on each command that writes a secrets file or hook into a grove
     */
    @ConfigurationProperties("orchard.knothole")
    public record KnotholeProperties(
        Path vaultPath,
        Path masterKeyPath,
        @DefaultValue("64KB") DataSize maxValueSize,
        @DefaultValue("60s") Duration staleTempAge,
        @DefaultValue("30s") Duration inspectTimeout,
        @DefaultValue("60s") Duration deliveryTimeout) {}

    @Bean
    public MasterKey masterKey(KnotholeProperties properties) {
        return MasterKey.loadOrCreate(properties.masterKeyPath());
    }

    @Bean
    public SecretVault secretVault(KnotholeProperties properties, MasterKey masterKey) {
        VaultSettings settings = new VaultSettings(
            Math.toIntExact(properties.maxValueSize().toBytes()), properties.staleTempAge());
        return new FileSecretVault(properties.vaultPath(), masterKey, settings);
    }
}
