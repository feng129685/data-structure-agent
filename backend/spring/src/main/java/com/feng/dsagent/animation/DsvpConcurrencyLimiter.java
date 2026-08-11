package com.feng.dsagent.animation;

import com.feng.dsagent.common.ApiException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
final class DsvpConcurrencyLimiter {

    private final Semaphore permits;

    DsvpConcurrencyLimiter(@Value("${app.animation.maximum-concurrent-simulations:4}") int maximumConcurrency) {
        if (maximumConcurrency < 1 || maximumConcurrency > 64) {
            throw new IllegalArgumentException("animation concurrency limit is invalid");
        }
        this.permits = new Semaphore(maximumConcurrency, true);
    }

    Permit acquire() {
        if (!permits.tryAcquire()) {
            throw new ApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                "DSVP_CONCURRENCY_LIMITED",
                "Animation requests are temporarily busy"
            );
        }
        return new Permit(permits);
    }

    static final class Permit implements AutoCloseable {
        private final Semaphore permits;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit(Semaphore permits) {
            this.permits = permits;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) permits.release();
        }
    }
}
