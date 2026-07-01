package dev.orchard.greenhouse;

import dev.orchard.core.model.DevcontainerSeed;
import dev.orchard.core.model.Prebuild;
import dev.orchard.core.model.PrebuildState;
import dev.orchard.core.model.Seed;
import dev.orchard.greenhouse.config.GreenhouseConfig;
import dev.orchard.harvest.DevcontainerParser;
import dev.orchard.roots.entity.PrebuildEntity;
import dev.orchard.roots.repository.PrebuildRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The PrebuildService orchestrates the prebuild lifecycle in the Greenhouse.
 * It manages the process of: clone repo -> parse devcontainer -> build image -> push -> save RIPE prebuild.
 * When a Cultivator plants a new Grove, the PrebuildService can provide a cached image
 * to skip the lengthy container build process.
 */
@Service
public class PrebuildService {

    private static final Logger log = LoggerFactory.getLogger(PrebuildService.class);

    private final PrebuildRepository prebuildRepository;
    private final ImageBuilder imageBuilder;
    private final GreenhouseConfig config;
    private final DevcontainerParser devcontainerParser;

    public PrebuildService(
            PrebuildRepository prebuildRepository,
            ImageBuilder imageBuilder,
            GreenhouseConfig config) {
        this.prebuildRepository = prebuildRepository;
        this.imageBuilder = imageBuilder;
        this.config = config;
        this.devcontainerParser = new DevcontainerParser();
    }

    /**
     * Triggers a prebuild for the given repository and branch.
     * The prebuild process: clone -> parse devcontainer.json -> build image -> push to registry.
     * Any existing RIPE prebuild for the same repo+branch will be composted (replaced).
     */
    @Transactional
    public Prebuild triggerPrebuild(String repositoryUrl, String branch) {
        log.info("Triggering prebuild for {} branch {}", repositoryUrl, branch);

        // Create prebuild record
        Prebuild prebuild = Prebuild.create(repositoryUrl, branch);
        prebuildRepository.save(PrebuildEntity.fromModel(prebuild));

        // Run the build asynchronously
        final Prebuild initialPrebuild = prebuild;
        Thread.ofVirtual().name("prebuild-" + prebuild.id()).start(() ->
            executePrebuild(initialPrebuild));

        return prebuild;
    }

    /**
     * Finds a usable (RIPE) prebuild for the given repository and branch.
     * Returns empty if no cached prebuild exists.
     */
    public Optional<Prebuild> findUsablePrebuild(String repositoryUrl, String branch) {
        return prebuildRepository
            .findByRepositoryUrlAndBranchAndState(repositoryUrl, branch, PrebuildState.RIPE.name())
            .map(PrebuildEntity::toModel)
            .filter(Prebuild::isUsable);
    }

    /**
     * Gets a prebuild by its ID.
     */
    public Optional<Prebuild> getPrebuild(UUID id) {
        return prebuildRepository.findById(id)
            .map(PrebuildEntity::toModel);
    }

    /**
     * Lists all prebuilds, ordered by creation time (newest first).
     */
    public List<Prebuild> listPrebuilds() {
        return prebuildRepository.findAll().stream()
            .map(PrebuildEntity::toModel)
            .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
            .toList();
    }

    /**
     * Lists all RIPE prebuilds that could be refreshed by the scheduler.
     */
    public List<Prebuild> listRipePrebuilds() {
        return prebuildRepository.findByState(PrebuildState.RIPE.name()).stream()
            .map(PrebuildEntity::toModel)
            .toList();
    }

    /**
     * Executes the full prebuild pipeline: clone, parse, build, push, save.
     */
    private void executePrebuild(Prebuild prebuild) {
        Path workDir = null;
        try {
            // Create temp working directory
            workDir = Path.of(config.workDir(), prebuild.id().toString());
            Files.createDirectories(workDir);

            // Clone repo
            Path repoDir = imageBuilder.cloneRepository(
                prebuild.repositoryUrl(), prebuild.branch(), workDir);

            // Get commit SHA
            String commitSha = imageBuilder.getCommitSha(repoDir);
            prebuild = prebuild.withCommitSha(commitSha);

            // Parse devcontainer.json
            DevcontainerSeed seed = discoverSeed(repoDir);

            // Build image
            String imageRef = imageBuilder.buildImage(
                prebuild.repositoryUrl(), prebuild.branch(), seed, repoDir);
            prebuild = prebuild.withImageRef(imageRef);

            // Push image to registry
            imageBuilder.pushImage(imageRef);

            // Compost any existing RIPE prebuild for this repo+branch
            compostExistingPrebuild(prebuild.repositoryUrl(), prebuild.branch(), prebuild.id());

            // Mark as RIPE
            prebuild = prebuild.withState(PrebuildState.RIPE);
            savePrebuild(prebuild);

            log.info("Prebuild {} completed successfully: {}", prebuild.id(), imageRef);

        } catch (Exception e) {
            log.error("Prebuild {} failed: {}", prebuild.id(), e.getMessage(), e);
            prebuild = prebuild.withState(PrebuildState.FAILED);
            savePrebuild(prebuild);
        } finally {
            // Clean up working directory
            cleanupWorkDir(workDir);
        }
    }

    private DevcontainerSeed discoverSeed(Path repoDir) {
        try {
            Optional<DevcontainerSeed> seed = devcontainerParser.discover(repoDir);
            if (seed.isPresent()) {
                log.info("Found devcontainer.json in repository");
                return seed.get();
            }
        } catch (Exception e) {
            log.warn("Failed to parse devcontainer.json, using default seed: {}", e.getMessage());
        }

        // Fall back to default seed
        log.info("No devcontainer.json found, using default seed for prebuild");
        return DevcontainerSeed.devcontainer()
            .name("orchard-workspace")
            .image("mcr.microsoft.com/devcontainers/base:ubuntu")
            .build();
    }

    /**
     * Compostes (marks as COMPOSTED) any existing RIPE prebuild for the given repo+branch,
     * excluding the specified prebuild ID (the one currently being built).
     */
    @Transactional
    public void compostExistingPrebuild(String repositoryUrl, String branch, UUID excludeId) {
        prebuildRepository
            .findByRepositoryUrlAndBranchAndState(repositoryUrl, branch, PrebuildState.RIPE.name())
            .ifPresent(entity -> {
                if (!entity.getId().equals(excludeId)) {
                    log.info("Composting previous prebuild {} for {} branch {}",
                        entity.getId(), repositoryUrl, branch);
                    entity.setState(PrebuildState.COMPOSTED.name());
                    prebuildRepository.save(entity);
                }
            });
    }

    @Transactional
    public void savePrebuild(Prebuild prebuild) {
        prebuildRepository.save(PrebuildEntity.fromModel(prebuild));
    }

    private void cleanupWorkDir(Path workDir) {
        if (workDir == null) {
            return;
        }
        try {
            // Use rm -rf via ProcessBuilder for reliable cleanup
            new ProcessBuilder("rm", "-rf", workDir.toString())
                .start()
                .waitFor();
        } catch (Exception e) {
            log.warn("Failed to clean up work directory {}: {}", workDir, e.getMessage());
        }
    }
}
