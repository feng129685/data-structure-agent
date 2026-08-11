package com.feng.dsagent.modelconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feng.dsagent.model.ModelClient;
import com.feng.dsagent.model.ModelClientException;
import com.feng.dsagent.model.ModelErrorCode;
import com.feng.dsagent.model.ModelMessage;
import com.feng.dsagent.model.ModelRequest;
import com.feng.dsagent.model.ModelResponse;
import com.feng.dsagent.model.ModelStreamHandler;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PersistedModelConfigClientTest {

    @Test
    void selectsThePersistedConfigurationWheneverADatabaseRowExists() {
        AtomicInteger environmentCalls = new AtomicInteger();
        AtomicInteger configuredCalls = new AtomicInteger();
        ModelClient environment = client("environment", environmentCalls);
        ModelClient configured = client("database", configuredCalls);
        ModelConfigRuntimeSettings settings = new ModelConfigRuntimeSettings(
            "custom",
            new ModelConfigResolvedTarget(
                URI.create("https://model.example/v1"),
                "model.example",
                443,
                List.of(publicAddress())
            ),
            "model-a",
            java.util.UUID.randomUUID().toString()
        );
        PersistedModelConfigClient client = new PersistedModelConfigClient(
            environment,
            () -> Optional.of(settings),
            ignored -> configured
        );

        ModelResponse response = client.complete(request());

        assertThat(response.content()).isEqualTo("database");
        assertThat(configuredCalls).hasValue(1);
        assertThat(environmentCalls).hasValue(0);
    }

    @Test
    void fallsBackToTheExistingEnvironmentClientOnlyWhenNoDatabaseRowExists() {
        AtomicInteger environmentCalls = new AtomicInteger();
        PersistedModelConfigClient client = new PersistedModelConfigClient(
            client("environment", environmentCalls),
            Optional::<ModelConfigRuntimeSettings>empty,
            ignored -> client("database", new AtomicInteger())
        );

        ModelResponse response = client.complete(request());

        assertThat(response.content()).isEqualTo("environment");
        assertThat(environmentCalls).hasValue(1);
    }

    @Test
    void doesNotFallBackToTheEnvironmentWhenAPersistedConfigurationCannotBeDecrypted() {
        AtomicInteger environmentCalls = new AtomicInteger();
        PersistedModelConfigClient client = new PersistedModelConfigClient(
            client("environment", environmentCalls),
            () -> {
                throw new ModelConfigRuntimeUnavailableException();
            },
            ignored -> client("database", new AtomicInteger())
        );

        assertThatThrownBy(() -> client.complete(request()))
            .isInstanceOf(ModelClientException.class)
            .extracting(error -> ((ModelClientException) error).errorCode())
            .isEqualTo(ModelErrorCode.MODEL_NOT_CONFIGURED);
        assertThat(environmentCalls).hasValue(0);
    }

    private ModelRequest request() {
        return new ModelRequest(List.of(new ModelMessage("user", "test")));
    }

    private InetAddress publicAddress() {
        try {
            return InetAddress.getByAddress(new byte[] {1, 1, 1, 1});
        } catch (java.net.UnknownHostException error) {
            throw new AssertionError(error);
        }
    }

    private ModelClient client(String content, AtomicInteger calls) {
        return new ModelClient() {
            @Override
            public ModelResponse complete(ModelRequest request) {
                calls.incrementAndGet();
                return new ModelResponse(content);
            }

            @Override
            public void stream(ModelRequest request, ModelStreamHandler handler) {
                calls.incrementAndGet();
                handler.onContent(content);
            }
        };
    }
}
