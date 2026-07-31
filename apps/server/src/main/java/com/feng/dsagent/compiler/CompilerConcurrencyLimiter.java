package com.feng.dsagent.compiler;

import com.feng.dsagent.common.ApiException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
final class CompilerConcurrencyLimiter {

    private final Semaphore globalPermits;
    private final int permitsPerClient;
    private final Map<String, ClientGate> clients = new HashMap<>();

    @Autowired
    CompilerConcurrencyLimiter(CompilerProperties properties) {
        this(properties.maximumConcurrentExecutions(), properties.maximumConcurrentExecutionsPerClient());
    }

    CompilerConcurrencyLimiter(int maximumConcurrentExecutions, int maximumConcurrentExecutionsPerClient) {
        if (maximumConcurrentExecutions < 1 || maximumConcurrentExecutionsPerClient < 1
                || maximumConcurrentExecutionsPerClient > maximumConcurrentExecutions) {
            throw new IllegalArgumentException("compiler concurrency limits are invalid");
        }
        this.globalPermits = new Semaphore(maximumConcurrentExecutions, true);
        this.permitsPerClient = maximumConcurrentExecutionsPerClient;
    }

    Permit acquire(String clientKey) {
        String key = clientKey == null || clientKey.isBlank() ? "unknown" : clientKey.trim();
        synchronized (clients) {
            if (!globalPermits.tryAcquire()) {
                throw limited();
            }
            ClientGate gate = clients.computeIfAbsent(key, ignored -> new ClientGate(permitsPerClient));
            if (!gate.permits.tryAcquire()) {
                globalPermits.release();
                throw limited();
            }
            gate.activePermits++;
            return new Permit(this, key, gate);
        }
    }

    private void release(String key, ClientGate gate) {
        synchronized (clients) {
            gate.permits.release();
            gate.activePermits--;
            globalPermits.release();
            if (gate.activePermits == 0) {
                clients.remove(key, gate);
            }
        }
    }

    private ApiException limited() {
        return new ApiException(
            HttpStatus.TOO_MANY_REQUESTS,
            "COMPILER_CONCURRENCY_LIMITED",
            "代码执行任务较多，请稍后重试"
        );
    }

    static final class Permit implements AutoCloseable {
        private final CompilerConcurrencyLimiter owner;
        private final String key;
        private final ClientGate gate;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit(CompilerConcurrencyLimiter owner, String key, ClientGate gate) {
            this.owner = owner;
            this.key = key;
            this.gate = gate;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.release(key, gate);
            }
        }
    }

    private static final class ClientGate {
        private final Semaphore permits;
        private int activePermits;

        private ClientGate(int permits) {
            this.permits = new Semaphore(permits, true);
        }
    }
}
