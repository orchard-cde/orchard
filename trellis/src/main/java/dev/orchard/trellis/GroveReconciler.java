package dev.orchard.trellis;

import dev.orchard.core.model.GroveState;
import dev.orchard.roots.entity.GroveEntity;
import dev.orchard.roots.repository.FruitRepository;
import dev.orchard.roots.repository.GroveRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

@Component
public class GroveReconciler implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GroveReconciler.class);
    private static final int LIVENESS_TIMEOUT_MS = 3000;

    private final GroveRepository groveRepository;
    private final FruitRepository fruitRepository;

    public GroveReconciler(GroveRepository groveRepository, FruitRepository fruitRepository) {
        this.groveRepository = groveRepository;
        this.fruitRepository = fruitRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Starting grove reconciliation...");

        List<GroveEntity> activeGroves = groveRepository.findActiveGroves();
        List<GroveEntity> preparingGroves = groveRepository.findByState(GroveState.PREPARING);
        List<GroveEntity> clearingGroves = groveRepository.findByState(GroveState.CLEARING);
        List<GroveEntity> dormantGroves = groveRepository.findByState(GroveState.DORMANT);

        List<GroveEntity> allGroves = new ArrayList<>(activeGroves);
        allGroves.addAll(preparingGroves);
        allGroves.addAll(clearingGroves);
        allGroves.addAll(dormantGroves);

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
                case DORMANT -> {
                    // stopGrove commits DORMANT before teardown runs asynchronously. Surviving
                    // fruit rows mean tearDownAndRecord's Phase 2 never completed, so teardown did
                    // not finish and the substrate may still exist.
                    //
                    // This deliberately over-reports: it cannot distinguish "Phase 1 died, VM is
                    // live" from "died between the state save and the row delete, VM is already
                    // gone". Over-reporting a possible leak is the safe direction. It also only
                    // observes — re-attempting teardown needs a provider, which this class does not
                    // have; see #228 / Plan 4.
                    if (!fruitRepository.findByGroveId(grove.getId()).isEmpty()) {
                        grove.setState(GroveState.ORPHANED);
                        groveRepository.save(grove);
                        orphaned++;
                        log.warn("Grove '{}' [{}] is DORMANT but its fruit rows survive — stop "
                            + "teardown did not complete; marked ORPHANED, operator action required",
                            grove.getName(), grove.getId());
                    }
                }
                default -> log.debug("Grove '{}' [{}] in state {} — skipping",
                        grove.getName(), grove.getId(), grove.getState());
            }
        }

        log.info("Grove reconciliation complete: {} alive, {} blighted, {} orphaned (of {} total)",
                alive, blighted, orphaned, allGroves.size());
    }

    private boolean isReachable(GroveEntity grove) {
        String ip = grove.getSeedlingIpAddress();
        Integer port = grove.getSeedlingSshPort();
        if (ip == null || port == null) {
            log.warn("Grove '{}' [{}] has no seedling address — treating as unreachable",
                    grove.getName(), grove.getId());
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), LIVENESS_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
