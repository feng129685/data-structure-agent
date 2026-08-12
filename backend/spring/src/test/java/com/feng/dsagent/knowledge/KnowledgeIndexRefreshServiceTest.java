package com.feng.dsagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class KnowledgeIndexRefreshServiceTest {

    @Test
    void clearsTheLiveSnapshotWhenEligibilityReloadFails() {
        KnowledgeSearchService search = new KnowledgeSearchService(List.of(chunk("stale-evidence")), 4);
        KnowledgeIndexRefreshService refresh = new KnowledgeIndexRefreshService(new FailingRepository(), search);

        refresh.refreshAfterEligibilityChange(new KnowledgeEligibilityChanged());

        assertThat(search.search("evidence", "03-stack-queue", 6, KnowledgeAudience.STUDENT)).isEmpty();
    }

    @Test
    void repeatedRefreshEventsAtomicallyReplaceTheSameEligibleSnapshot() {
        KnowledgeSearchService search = new KnowledgeSearchService(List.of(), 4);
        KnowledgeChunk eligible = chunk("eligible-evidence");
        KnowledgeIndexRefreshService refresh = new KnowledgeIndexRefreshService(
            new FixedRepository(List.of(eligible)),
            search
        );

        refresh.refreshAfterEligibilityChange(new KnowledgeEligibilityChanged());
        refresh.refreshAfterEligibilityChange(new KnowledgeEligibilityChanged());

        assertThat(search.search("evidence", "03-stack-queue", 6, KnowledgeAudience.STUDENT))
            .extracting(result -> result.chunk().id())
            .containsExactly("eligible-evidence");
    }

    @Test
    void serializesConcurrentEligibilityRefreshes() throws Exception {
        KnowledgeSearchService search = new KnowledgeSearchService(List.of(), 4);
        BlockingRepository repository = new BlockingRepository(List.of(chunk("concurrent-evidence")));
        KnowledgeIndexRefreshService refresh = new KnowledgeIndexRefreshService(repository, search);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = workers.submit(() -> refresh.refreshAfterEligibilityChange(new KnowledgeEligibilityChanged()));
            assertThat(repository.firstFetchStarted.await(1, TimeUnit.SECONDS)).isTrue();

            Future<?> second = workers.submit(() -> {
                repository.secondWorkerStarted.countDown();
                refresh.refreshAfterEligibilityChange(new KnowledgeEligibilityChanged());
            });
            assertThat(repository.secondWorkerStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(repository.secondFetchStarted.await(150, TimeUnit.MILLISECONDS)).isFalse();

            repository.releaseFetch.countDown();
            first.get(1, TimeUnit.SECONDS);
            second.get(1, TimeUnit.SECONDS);

            assertThat(repository.maximumConcurrentFetches.get()).isEqualTo(1);
            assertThat(search.search("evidence", "03-stack-queue", 6, KnowledgeAudience.STUDENT))
                .extracting(result -> result.chunk().id())
                .containsExactly("concurrent-evidence");
        } finally {
            repository.releaseFetch.countDown();
            workers.shutdownNow();
            workers.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    private static KnowledgeChunk chunk(String id) {
        return new KnowledgeChunk(
            id,
            "03-stack-queue",
            "Evidence evidence",
            "Evidence evidence evidence for a reviewed stack lesson.",
            "fixtures/" + id + ".md",
            null,
            "CLASSROOM_ONLY"
        );
    }

    private static final class FailingRepository implements KnowledgeChunkRepository {

        @Override
        public void replaceTextbook(Collection<KnowledgeChunk> chunks) {
        }

        @Override
        public List<KnowledgeChunk> findPublished() {
            throw new IllegalStateException("database unavailable");
        }
    }

    private static final class FixedRepository implements KnowledgeChunkRepository {

        private final List<KnowledgeChunk> chunks;

        private FixedRepository(List<KnowledgeChunk> chunks) {
            this.chunks = List.copyOf(chunks);
        }

        @Override
        public void replaceTextbook(Collection<KnowledgeChunk> chunks) {
        }

        @Override
        public List<KnowledgeChunk> findPublished() {
            return chunks;
        }
    }

    private static final class BlockingRepository implements KnowledgeChunkRepository {

        private final List<KnowledgeChunk> chunks;
        private final AtomicInteger activeFetches = new AtomicInteger();
        private final AtomicInteger maximumConcurrentFetches = new AtomicInteger();
        private final AtomicInteger fetches = new AtomicInteger();
        private final CountDownLatch firstFetchStarted = new CountDownLatch(1);
        private final CountDownLatch secondFetchStarted = new CountDownLatch(1);
        private final CountDownLatch secondWorkerStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFetch = new CountDownLatch(1);

        private BlockingRepository(List<KnowledgeChunk> chunks) {
            this.chunks = List.copyOf(chunks);
        }

        @Override
        public void replaceTextbook(Collection<KnowledgeChunk> chunks) {
        }

        @Override
        public List<KnowledgeChunk> findPublished() {
            int fetch = fetches.incrementAndGet();
            int active = activeFetches.incrementAndGet();
            maximumConcurrentFetches.accumulateAndGet(active, Math::max);
            if (fetch == 1) {
                firstFetchStarted.countDown();
            } else {
                secondFetchStarted.countDown();
            }
            try {
                if (!releaseFetch.await(1, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test refresh was not released");
                }
                return chunks;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test refresh interrupted", error);
            } finally {
                activeFetches.decrementAndGet();
            }
        }
    }
}
