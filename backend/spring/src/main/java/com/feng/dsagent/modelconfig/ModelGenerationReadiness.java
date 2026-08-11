package com.feng.dsagent.modelconfig;

import com.feng.dsagent.aiquota.AiQuotaExecutionProperties;
import com.feng.dsagent.common.ApiException;
import com.feng.dsagent.model.ModelProperties;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

@Component
public final class ModelGenerationReadiness {

    private final ModelConfigRepository repository;
    private final ModelConfigMasterKeySource masterKeySource;
    private final ModelConfigCrypto crypto;
    private final ModelConfigUrlValidator urlValidator;
    private final ModelProperties environment;
    private final AiQuotaExecutionProperties aiQuota;

    ModelGenerationReadiness(
        ModelConfigRepository repository,
        ModelConfigMasterKeySource masterKeySource,
        ModelConfigCrypto crypto,
        ModelConfigUrlValidator urlValidator,
        ModelProperties environment,
        AiQuotaExecutionProperties aiQuota
    ) {
        this.repository = repository;
        this.masterKeySource = masterKeySource;
        this.crypto = crypto;
        this.urlValidator = urlValidator;
        this.environment = environment;
        this.aiQuota = aiQuota;
    }

    public State current() {
        return persisted().orElseGet(this::environmentState);
    }

    Optional<State> persisted() {
        return repository.find().map(this::persistedState);
    }

    private State persistedState(ModelConfigRepository.StoredModelConfig stored) {
        if (!stored.enabled()) {
            return State.unavailable(Reason.PERSISTED_CONFIGURATION_DISABLED);
        }
        if (stored.dailyTokenQuota() <= 0) {
            return State.unavailable(Reason.PERSISTED_QUOTA_NOT_CONFIGURED);
        }
        SecretKey masterKey = masterKeySource.masterKey().orElse(null);
        if (masterKey == null || isBlank(stored.provider()) || isBlank(stored.model())) {
            return State.unavailable(Reason.PERSISTED_CONFIGURATION_UNAVAILABLE);
        }
        try {
            urlValidator.resolve(stored.baseUrl());
            String apiKey = crypto.decrypt(stored.apiKeyCiphertext(), masterKey, ModelConfigKeyBinding.forStored(stored));
            if (isBlank(apiKey) || !PinnedHttpsTransport.isSafeHeaderValue(apiKey)) {
                return State.unavailable(Reason.PERSISTED_CONFIGURATION_UNAVAILABLE);
            }
            return State.eligible(Reason.PERSISTED_CONFIGURATION_READY, stored.dailyTokenQuota());
        } catch (ModelConfigCrypto.CryptoFailure | ApiException error) {
            return State.unavailable(Reason.PERSISTED_CONFIGURATION_UNAVAILABLE);
        }
    }

    private State environmentState() {
        if (!isBlank(environment.apiKey())
            && !isBlank(environment.baseUrl())
            && !isBlank(environment.name())) {
            if (aiQuota.dailyTokenQuota() < 1) {
                return State.unavailable(Reason.ENVIRONMENT_QUOTA_NOT_CONFIGURED);
            }
            return State.eligible(Reason.ENVIRONMENT_CONFIGURATION_READY, aiQuota.dailyTokenQuota());
        }
        return State.unavailable(Reason.ENVIRONMENT_CONFIGURATION_INCOMPLETE);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public enum Reason {
        PERSISTED_CONFIGURATION_READY,
        PERSISTED_CONFIGURATION_DISABLED,
        PERSISTED_QUOTA_NOT_CONFIGURED,
        PERSISTED_CONFIGURATION_UNAVAILABLE,
        ENVIRONMENT_CONFIGURATION_READY,
        ENVIRONMENT_QUOTA_NOT_CONFIGURED,
        ENVIRONMENT_CONFIGURATION_INCOMPLETE
    }

    public record State(boolean eligible, Reason reason, Long dailyTokenQuota) {

        private static State eligible(Reason reason, long dailyTokenQuota) {
            return new State(true, reason, dailyTokenQuota);
        }

        private static State unavailable(Reason reason) {
            return new State(false, reason, null);
        }
    }
}
