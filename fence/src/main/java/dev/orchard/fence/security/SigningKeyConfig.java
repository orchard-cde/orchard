package dev.orchard.fence.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.text.ParseException;
import java.util.UUID;

@Configuration
public class SigningKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(SigningKeyConfig.class);

    @Bean
    JWKSet signingKey(SigningKeyProperties properties) throws IOException, ParseException, JOSEException {
        File keyFile = new File(properties.getPath());

        if (keyFile.exists()) {
            log.info("Loading existing signing key from {}", keyFile.getAbsolutePath());
            return JWKSet.load(keyFile);
        }

        log.info("Generating new signing key at {}", keyFile.getAbsolutePath());
        RSAKey rsaKey = new RSAKey.Builder(new RSAKeyGenerator(2048).generate())
                .keyID(UUID.randomUUID().toString())
                .build();
        JWKSet jwkSet = new JWKSet(rsaKey);

        keyFile.getParentFile().mkdirs();
        FileAttribute<?> ownerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
        Files.createFile(keyFile.toPath(), ownerOnly);
        Files.writeString(keyFile.toPath(), jwkSet.toString(false));
        log.info("Signing key generated and saved");

        return jwkSet;
    }
}
