package stirling.software.SPDF.service.pdflunna;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounds active documents and waiting requests across the PDFLunna endpoints. */
@Component
public class PdfLunnaAdmission {
    private final Semaphore active;
    private final Semaphore waiting;
    private final long timeoutSeconds;

    public PdfLunnaAdmission(
            @Value("${pdflunna.processing.max-concurrent:2}") int maxConcurrent,
            @Value("${pdflunna.processing.max-queued:8}") int maxQueued,
            @Value("${pdflunna.processing.queue-timeout-seconds:30}") long timeoutSeconds) {
        if (maxConcurrent < 1 || maxQueued < 0 || timeoutSeconds < 1) {
            throw new IllegalArgumentException("Invalid PDFLunna processing limits");
        }
        active = new Semaphore(maxConcurrent, true);
        waiting = new Semaphore(maxQueued, true);
        this.timeoutSeconds = timeoutSeconds;
    }

    public Permit acquire() {
        try {
            // Unlike tryAcquire(), the timed form honors previously queued requests.
            if (active.tryAcquire(0, TimeUnit.SECONDS)) {
                return new Permit();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusyException("PDF processing queue wait interrupted");
        }
        if (!waiting.tryAcquire()) {
            throw new BusyException("PDF processing queue is full");
        }
        try {
            if (!active.tryAcquire(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new BusyException("PDF processing queue wait timed out");
            }
            return new Permit();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusyException("PDF processing queue wait interrupted");
        } finally {
            waiting.release();
        }
    }

    public final class Permit implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit() {}

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                active.release();
            }
        }
    }

    public static class BusyException extends RuntimeException {
        public BusyException(String message) {
            super(message);
        }
    }
}
