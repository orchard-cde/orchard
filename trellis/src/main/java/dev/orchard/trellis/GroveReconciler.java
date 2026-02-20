package dev.orchard.trellis;

import dev.orchard.core.model.GroveState;
import dev.orchard.roots.entity.GroveEntity;
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

    public GroveReconciler(GroveRepository groveRepository) {
        this.groveRepository = groveRepository;
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
        int cleared = 0;
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
                    grove.setState(GroveState.CLEARED);
                    groveRepository.save(grove);
                    cleared++;
                    log.info("Grove '{}' [{}] had interrupted teardown — marked CLEARED",
                            grove.getName(), grove.getId());
                }
                default -> log.debug("Grove '{}' [{}] in state {} — skipping",
                        grove.getName(), grove.getId(), grove.getState());
            }
        }

        log.info("Grove reconciliation complete: {} alive, {} blighted, {} cleared (of {} total)",
                alive, blighted, cleared, allGroves.size());
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
