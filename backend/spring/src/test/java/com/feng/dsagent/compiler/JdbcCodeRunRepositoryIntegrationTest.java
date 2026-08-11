package com.feng.dsagent.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class JdbcCodeRunRepositoryIntegrationTest {

    @Autowired
    private CodeRunRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void findOwnedReturnsOnlyTheRequestingUsersRun() {
        long ownerId = 8901L;
        long otherUserId = 8902L;
        jdbc.update(
            "INSERT INTO users (id, email, password_hash) VALUES (?, ?, ?)",
            ownerId,
            "code-run-owner@example.com",
            "hash"
        );
        jdbc.update(
            "INSERT INTO users (id, email, password_hash) VALUES (?, ?, ?)",
            otherUserId,
            "code-run-other@example.com",
            "hash"
        );

        String runId = repository.save(
            ownerId,
            "03-stack-queue",
            new RunCodeRequest("c", "int main(void) { return 0; }", "input", "03-stack-queue"),
            new RunCodeResponse("c", "success", "output", "", 12L, null)
        );

        assertThat(repository.findOwned(runId, ownerId))
            .get()
            .satisfies(run -> {
                assertThat(run.id()).isEqualTo(runId);
                assertThat(run.chapterId()).isEqualTo("03-stack-queue");
                assertThat(run.language()).isEqualTo("c");
                assertThat(run.code()).isEqualTo("int main(void) { return 0; }");
                assertThat(run.stdin()).isEqualTo("input");
                assertThat(run.stdout()).isEqualTo("output");
                assertThat(run.stderr()).isEmpty();
                assertThat(run.status()).isEqualTo("success");
            });
        assertThat(repository.findOwned(runId, otherUserId)).isEmpty();
        assertThat(repository.findOwned("missing-run", ownerId)).isEmpty();
    }
}
