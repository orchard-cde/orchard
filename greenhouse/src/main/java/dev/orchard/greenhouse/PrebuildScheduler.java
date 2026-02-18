package dev.orchard.greenhouse;

import dev.orchard.core.model.Prebuild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The PrebuildScheduler periodically tends the Greenhouse by refreshing
 * existing prebuilds. When enabled, it checks all RIPE prebuilds and
 * triggers a rebuild to ensure cached images stay up-to-date with
 * the latest commits on their respective branches.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "orchard.greenhouse.prebuild-enabled", havingValue = "true")
public class PrebuildScheduler {

    private static final Logger log = LoggerFactory.getLogger(PrebuildScheduler.class);

    private final PrebuildService prebuildService;

    public PrebuildScheduler(PrebuildService prebuildService) {
        this.prebuildService = prebuildService;
    }

    /**
     * Periodically refreshes all RIPE prebuilds by triggering a new build.
     * The interval is configured via orchard.greenhouse.prebuild-schedule-millis.
     * Defaults to running every 60 minutes (3600000 ms).
     */
    @Scheduled(fixedDelayString = "${orchard.greenhouse.prebuild-schedule-millis:3600000}")
    public void refreshPrebuilds() {
        log.info("Greenhouse scheduler: checking for prebuilds to refresh");

        List<Prebuild> ripePrebuilds = prebuildService.listRipePrebuilds();
        if (ripePrebuilds.isEmpty()) {
            log.debug("No RIPE prebuilds to refresh");
            return;
        }

        log.info("Refreshing {} RIPE prebuild(s)", ripePrebuilds.size());

        for (Prebuild prebuild : ripePrebuilds) {
            try {
                log.info("Refreshing prebuild for {} branch {}",
                    prebuild.repositoryUrl(), prebuild.branch());
                prebuildService.triggerPrebuild(prebuild.repositoryUrl(), prebuild.branch());
            } catch (Exception e) {
                log.error("Failed to refresh prebuild for {} branch {}: {}",
                    prebuild.repositoryUrl(), prebuild.branch(), e.getMessage());
            }
        }
    }
}
