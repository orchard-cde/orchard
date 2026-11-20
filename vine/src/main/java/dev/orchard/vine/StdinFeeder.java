package dev.orchard.vine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicReference;

final class StdinFeeder {

    private static final Logger log = LoggerFactory.getLogger(StdinFeeder.class);

    private StdinFeeder() {
    }

    record Feed(Thread thread, AtomicReference<Throwable> failure) {

        void join(long millis) throws InterruptedException {
            thread.join(millis);
        }

        boolean failed() {
            return failure.get() != null;
        }
    }

    static Feed feed(Process process, byte[] stdin) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofVirtual().name("stdin-feed").start(() -> {
            OutputStream out = process.getOutputStream();
            try {
                out.write(stdin);
            } catch (Throwable t) {
                failure.set(t);
                log.debug("stdin feed failed: {}", t.getClass().getName());
            } finally {
                try {
                    out.close();
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }
        });
        return new Feed(thread, failure);
    }
}
