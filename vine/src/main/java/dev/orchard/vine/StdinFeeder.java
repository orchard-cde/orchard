package dev.orchard.vine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Writes a child's stdin from its own thread and judges the write only once the child has exited.
 *
 * <p>Writing on the caller's thread would block in the pipe once the child stops reading, so the
 * caller would never reach its {@code waitFor} timeout and never kill the child. Judging the write
 * early would report "broken pipe" for a child that failed before reading, hiding its exit code and
 * stderr. A child that exits 0 without consuming its input is still an error: it returned success
 * without receiving what it was meant to write.
 */
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

        void finish(long graceMillis, int exitCode, String command) throws IOException, InterruptedException {
            join(graceMillis);
            if (exitCode == 0 && failed()) {
                throw new IOException("stdin feed failed (child exited 0 without consuming its input): " + command);
            }
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
