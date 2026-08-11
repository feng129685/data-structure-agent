package com.feng.dsagent.modelconfig;

import com.feng.dsagent.common.ApiException;
import java.time.Duration;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

@Component
class DatabaseModelConfigRuntimeSettingsSource implements ModelConfigRuntimeSettingsSource {

    private final ModelConfigRepository repository;
    private final ModelConfigMasterKeySource masterKeySource;
    private final ModelConfigCrypto crypto;
    private final ModelConfigUrlValidator urlValidator;

    DatabaseModelConfigRuntimeSettingsSource(
        ModelConfigRepository repository,
        ModelConfigMasterKeySource masterKeySource,
        ModelConfigCrypto crypto,
        ModelConfigUrlValidator urlValidator
    ) {
        this.repository = repository;
        this.masterKeySource = masterKeySource;
        this.crypto = crypto;
        this.urlValidator = urlValidator;
    }

    @Override
    public Optional<ModelConfigRuntimeSettings> current() {
        Optional<ModelConfigRepository.StoredModelConfig> stored = repository.find();
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        if (!stored.get().enabled() || stored.get().dailyTokenQuota() <= 0) {
            throw new ModelConfigRuntimeUnavailableException();
        }
        SecretKey masterKey = masterKeySource.masterKey().orElseThrow(ModelConfigRuntimeUnavailableException::new);
        try {
            ModelConfigResolvedTarget target = urlValidator.resolve(stored.get().baseUrl());
            String apiKey = crypto.decrypt(
                stored.get().apiKeyCiphertext(),
                masterKey,
                ModelConfigKeyBinding.forStored(stored.get())
            );
            return Optional.of(new ModelConfigRuntimeSettings(
                stored.get().provider(),
                target,
                stored.get().model(),
                apiKey,
                stored.get().temperature(),
                stored.get().maxOutputTokens(),
                Duration.ofMillis(stored.get().requestTimeoutMs()),
                stored.get().retryCount(),
                stored.get().dailyTokenQuota()
            ));
        } catch (ModelConfigCrypto.CryptoFailure | ApiException error) {
            throw new ModelConfigRuntimeUnavailableException();
        }
    }
}
