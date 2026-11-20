package dev.orchard.vine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;

final class StdinFeeder {

    private static final Logger log = LoggerFactory.getLogger(StdinFeeder.class);

    private StdinFeeder() {
    }

    static Thread feed(Process process, byte[] stdin) {
        return Thread.ofVirtual().name("stdin-feed").start(() -> {
            try (OutputStream out = process.getOutputStream()) {
                out.write(stdin);
            } catch (IOException e) {
                log.debug("child closed stdin before the feed finished");
            }
        });
    }
}
