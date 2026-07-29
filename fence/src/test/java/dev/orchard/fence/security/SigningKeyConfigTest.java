package dev.orchard.fence.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SigningKeyConfigTest {

    @TempDir
    Path tempDir;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SigningKeyConfig.class)
            .withBean(SigningKeyProperties.class, () -> {
                SigningKeyProperties props = new SigningKeyProperties();
                props.setPath(tempDir.resolve("signing-key.jwk").toString());
                return props;
            });

    @Test
    void loadsExistingKey() throws Exception {
        File keyFile = tempDir.resolve("signing-key.jwk").toFile();
        RSAKey rsaKey = new RSAKey.Builder(new RSAKeyGenerator(2048).generate())
                .keyID("test-key")
                .build();
        JWKSet jwkSet = new JWKSet(rsaKey);
        keyFile.getParentFile().mkdirs();
        Files.writeString(keyFile.toPath(), jwkSet.toString());

        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(JWKSet.class);
            JWKSet loaded = context.getBean(JWKSet.class);
            assertThat(loaded).isNotNull();
            assertThat(loaded.getKeys()).hasSize(1);
            assertThat(loaded.getKeys().get(0).getKeyID()).isEqualTo("test-key");
        });
    }

    @Test
    void generatesKeyWhenMissing() {
        File keyFile = tempDir.resolve("signing-key.jwk").toFile();
        assertThat(keyFile).doesNotExist();

        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(JWKSet.class);
            JWKSet jwkSet = context.getBean(JWKSet.class);
            assertThat(jwkSet).isNotNull();
            assertThat(jwkSet.getKeys()).hasSize(1);
            assertThat(keyFile).exists();
        });
    }
}
