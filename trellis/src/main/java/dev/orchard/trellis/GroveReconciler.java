package dev.orchard.trellis;

import dev.orchard.core.model.GroveState;
import dev.orchard.core.model.Plot;
import dev.orchard.core.model.PlotState;
import dev.orchard.nursery.ProviderRegistry;
import dev.orchard.roots.entity.GroveEntity;
import dev.orchard.roots.repository.GroveRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class GroveReconciler implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GroveReconciler.class);

    private final GroveRepository groveRepository;
    private final ProviderRegistry registry;
    private final Duration inspectTimeout;

    @Autowired
    public GroveReconciler(GroveRepository groveRepository, ProviderRegistry registry) {
        this(groveRepository, registry, Duration.ofSeconds(10));
    }

    GroveReconciler(GroveRepository groveRepository, ProviderRegistry registry, Duration inspectTimeout) {
        this.groveRepository = groveRepository;
        this.registry = registry;
        this.inspectTimeout = inspectTimeout;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Starting grove reconciliation...");

        List<GroveEntity> activeGroves = groveRepository.findActiveGroves();
        List<GroveEntity> preparingGroves = groveRepository.findByState(GroveState.PREPARING);
        List<GroveEntity> clearingGroves = groveRepository.findByState(GroveState.CLEARING);

        List<GroveEntity> allGroves = new ArrayList<>(activeGroves);
        allGroves.addAll(preparingGroves);
        allGroves.addAll(clearingGroves);

        if (allGroves.isEmpty()) {
            log.info("No groves require reconciliation");
            return;
        }

        int blighted = 0;
        int orphaned = 0;
        int alive = 0;

        for (GroveEntity grove : allGroves) {
            switch (grove.getState()) {
                case FLOURISHING -> {
                    if (isReachable(grove)) {
                        alive++;
                        log.info("Grove '{}' [{}] is still reachable — leaving FLOURISHING",
                                grove.getName(), grove.getId());
                    } else {
                        grove.setState(GroveState.BLIGHTED);
                        groveRepository.save(grove);
                        blighted++;
                        log.warn("Grove '{}' [{}] is unreachable — marked BLIGHTED",
                                grove.getName(), grove.getId());
                    }
                }
                case PREPARING, PLANTING, GROWING -> {
                    grove.setState(GroveState.BLIGHTED);
                    groveRepository.save(grove);
                    blighted++;
                    log.warn("Grove '{}' [{}] was stuck in {} — marked BLIGHTED",
                            grove.getName(), grove.getId(), grove.getState());
                }
                case CLEARING -> {
                    grove.setState(GroveState.ORPHANED);
                    groveRepository.save(grove);
                    orphaned++;
                    log.warn("Grove '{}' [{}] had interrupted teardown and no teardown was "
                        + "re-attempted — marked ORPHANED; operator action required",
                        grove.getName(), grove.getId());
                }
                default -> log.debug("Grove '{}' [{}] in state {} — skipping",
                        grove.getName(), grove.getId(), grove.getState());
            }
        }

        log.info("Grove reconciliation complete: {} alive, {} blighted, {} orphaned "
                + "(of {} total)", alive, blighted, orphaned, allGroves.size());
    }

    private boolean isReachable(GroveEntity grove) {
        String providerId = null;
        try {
            Plot plot = grove.plot();
            providerId = plot.providerId();
            return registry.require(providerId).inspect(plot)
                .orTimeout(inspectTimeout.toMillis(), TimeUnit.MILLISECONDS).join().state() == PlotState.SAPLING;
        } catch (Exception e) {
            log.error("Grove '{}' [{}] liveness inspection failed (provider '{}') — treating as unreachable",
                    grove.getName(), grove.getId(), providerId, e);
            return false;
        }
    }
}
