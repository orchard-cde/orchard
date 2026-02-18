package dev.orchard.nursery.aws;

import dev.orchard.core.model.Seedling;
import dev.orchard.nursery.SeedlingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * AWS EC2 implementation of SeedlingProvider.
 * Provisions EC2 instances as Seedlings using cloud-init for initial setup.
 *
 * <p>TODO: Implement using AWS SDK v2 (software.amazon.awssdk:ec2).
 */
public class Ec2SeedlingProvider implements SeedlingProvider {

    private static final Logger log = LoggerFactory.getLogger(Ec2SeedlingProvider.class);
    private static final String PROVIDER_ID = "aws-ec2";

    private final Ec2Config config;

    public Ec2SeedlingProvider(Ec2Config config) {
        this.config = config;
    }

    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }

    @Override
    public CompletableFuture<Seedling> plant(Seedling seedling) {
        throw new UnsupportedOperationException("AWS EC2 provider not yet implemented");
    }

    @Override
    public CompletableFuture<Seedling> water(Seedling seedling) {
        throw new UnsupportedOperationException("AWS EC2 provider not yet implemented");
    }

    @Override
    public CompletableFuture<Seedling> dormant(Seedling seedling) {
        throw new UnsupportedOperationException("AWS EC2 provider not yet implemented");
    }

    @Override
    public CompletableFuture<Void> uproot(Seedling seedling) {
        throw new UnsupportedOperationException("AWS EC2 provider not yet implemented");
    }

    @Override
    public CompletableFuture<Seedling> inspect(Seedling seedling) {
        throw new UnsupportedOperationException("AWS EC2 provider not yet implemented");
    }

    @Override
    public boolean isAvailable() {
        log.warn("AWS EC2 provider is registered but not yet implemented");
        return false;
    }
}
