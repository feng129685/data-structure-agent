package com.feng.dsagent.modelconfig;

import com.feng.dsagent.common.ApiException;
import com.feng.dsagent.security.AuthenticatedUser;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKey;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModelConfigService {

    private final ModelConfigMasterKeySource masterKeySource;
    private final ModelConfigRepository repository;
    private final ModelConfigCrypto crypto;
    private final ModelConfigUrlValidator urlValidator;
    private final ModelConfigConnectionTester connectionTester;
    private final ModelGenerationReadiness generationReadiness;

    ModelConfigService(
        ModelConfigMasterKeySource masterKeySource,
        ModelConfigRepository repository,
        ModelConfigCrypto crypto,
        ModelConfigUrlValidator urlValidator,
        ModelConfigConnectionTester connectionTester,
        ModelGenerationReadiness generationReadiness
    ) {
        this.masterKeySource = masterKeySource;
        this.repository = repository;
        this.crypto = crypto;
        this.urlValidator = urlValidator;
        this.connectionTester = connectionTester;
        this.generationReadiness = generationReadiness;
    }

    ModelConfigController.ModelConfigCapabilityView capability() {
        CapabilityState accessState = configurationAccessState();
        if (!accessState.available()) {
            return new ModelConfigController.ModelConfigCapabilityView(false, accessState.reason(), null);
        }
        CapabilityState generationState = capabilityState();
        return new ModelConfigController.ModelConfigCapabilityView(
            generationState.available(),
            generationState.reason(),
            view(repository.find().orElseThrow())
        );
    }

    private CapabilityState configurationAccessState() {
        Optional<SecretKey> masterKey = masterKeySource.masterKey();
        if (masterKey.isEmpty()) {
            return new CapabilityState(false, "MASTER_KEY_UNAVAILABLE");
        }
        Optional<ModelConfigRepository.StoredModelConfig> stored = repository.find();
        if (stored.isEmpty()) {
            return new CapabilityState(false, "NOT_CONFIGURED");
        }
        try {
            crypto.decrypt(stored.get().apiKeyCiphertext(), masterKey.get(), ModelConfigKeyBinding.forStored(stored.get()));
            return new CapabilityState(true, null);
        } catch (ModelConfigCrypto.CryptoFailure ignored) {
            return new CapabilityState(false, "MODEL_CONFIG_UNAVAILABLE");
        }
    }

    public CapabilityState capabilityState() {
        Optional<SecretKey> masterKey = masterKeySource.masterKey();
        if (masterKey.isEmpty()) {
            return new CapabilityState(false, "MASTER_KEY_UNAVAILABLE");
        }
        Optional<ModelGenerationReadiness.State> persisted = generationReadiness.persisted();
        if (persisted.isEmpty()) {
            return new CapabilityState(false, "NOT_CONFIGURED");
        }
        return capabilityState(persisted.get());
    }

    private CapabilityState capabilityState(ModelGenerationReadiness.State state) {
        return switch (state.reason()) {
            case PERSISTED_CONFIGURATION_READY -> new CapabilityState(true, null);
            case PERSISTED_CONFIGURATION_DISABLED, PERSISTED_QUOTA_NOT_CONFIGURED ->
                new CapabilityState(false, state.reason().name());
            case PERSISTED_CONFIGURATION_UNAVAILABLE -> new CapabilityState(false, "MODEL_CONFIG_UNAVAILABLE");
            default -> new CapabilityState(false, "MODEL_CONFIG_UNAVAILABLE");
        };
    }

    @Transactional
    ModelConfigController.ModelConfigView update(
        ModelConfigController.UpdateModelConfigRequest request,
        AuthenticatedUser actor,
        String requestId
    ) {
        SecretKey masterKey = requiredMasterKey();
        URI baseUrl = urlValidator.validate(request.baseUrl());
        String provider = request.provider().strip();
        String model = request.model().strip();
        Optional<ModelConfigRepository.StoredModelConfig> existing = repository.find();
        ModelConfigGenerationControls controls = controls(request, existing);
        String suppliedApiKey = normalizeOptional(request.apiKey());
        boolean targetChanged = existing.isEmpty()
            || !existing.get().provider().equals(provider)
            || !existing.get().baseUrl().equals(baseUrl.toString());
        boolean connectionEvidenceInvalidated = suppliedApiKey != null
            || targetChanged
            || existing.isPresent() && !existing.get().model().equals(model);
        String ciphertext;
        if (suppliedApiKey == null) {
            if (targetChanged) {
                throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "MODEL_CONFIG_API_KEY_REQUIRED",
                    "模型服务地址或提供商变更时必须重新输入密钥"
                );
            }
            ciphertext = existing.get().apiKeyCiphertext();
        } else {
            ciphertext = crypto.encrypt(
                suppliedApiKey,
                masterKey,
                ModelConfigKeyBinding.forConfiguration(ModelConfigRepository.CONFIGURATION_ID, provider, baseUrl.toString())
            );
        }
        ModelConfigRepository.StoredModelConfig stored = repository.save(
            provider,
            baseUrl.toString(),
            model,
            ciphertext,
            controls,
            connectionEvidenceInvalidated
        );
        appendAudit(actor, "MODEL_CONFIG_UPDATED", "SUCCESS", requestId, existing.orElse(null), stored);
        return view(stored);
    }

    @Transactional
    ModelConfigController.ModelConfigConnectionTestView testConnection(AuthenticatedUser actor, String requestId) {
        SecretKey masterKey = requiredMasterKey();
        ModelConfigRepository.StoredModelConfig stored = repository.find().orElseThrow(() -> new ApiException(
            HttpStatus.CONFLICT,
            "MODEL_CONFIG_NOT_CONFIGURED",
            "尚未保存模型配置"
        ));
        ModelConfigResolvedTarget target = urlValidator.resolve(stored.baseUrl());
        String apiKey;
        try {
            apiKey = crypto.decrypt(stored.apiKeyCiphertext(), masterKey, ModelConfigKeyBinding.forStored(stored));
        } catch (ModelConfigCrypto.CryptoFailure error) {
            throw new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "MODEL_CONFIG_UNAVAILABLE",
                "模型配置当前不可用"
            );
        }
        ModelConfigConnectionResult result;
        long connectionTestStartedAt = System.nanoTime();
        try {
            result = connectionTester.test(new ModelConfigConnection(
                stored.provider(),
                target,
                stored.model(),
                apiKey
            ));
        } catch (RuntimeException ignored) {
            result = new ModelConfigConnectionResult(false, "CONNECTION_FAILED");
        }
        long connectionTestElapsedMs = Math.max(
            0L,
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - connectionTestStartedAt)
        );
        String code = connectionCode(result);
        ModelConfigRepository.StoredModelConfig after = repository.recordConnectionTest(code);
        repository.appendAuditEvent(
            actor.userId(),
            "MODEL_CONFIG_CONNECTION_TESTED",
            result.connected() ? "SUCCESS" : "FAILED",
            safeRequestId(requestId),
            summary(stored),
            summary(after) + ";connectionTestElapsedMs=" + connectionTestElapsedMs
                + ";credentialRedacted=true"
        );
        return new ModelConfigController.ModelConfigConnectionTestView(result.connected(), code);
    }

    private SecretKey requiredMasterKey() {
        return masterKeySource.masterKey().orElseThrow(() -> new ApiException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "MODEL_CONFIG_UNAVAILABLE",
            "模型配置当前不可用"
        ));
    }

    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    private ModelConfigGenerationControls controls(
        ModelConfigController.UpdateModelConfigRequest request,
        Optional<ModelConfigRepository.StoredModelConfig> existing
    ) {
        ModelConfigGenerationControls fallback = existing
            .map(ModelConfigGenerationControls::from)
            .orElseGet(ModelConfigGenerationControls::defaults);
        double temperature = request.temperature() == null ? fallback.temperature() : request.temperature();
        int maxOutputTokens = request.maxOutputTokens() == null ? fallback.maxOutputTokens() : request.maxOutputTokens();
        long requestTimeoutMs = request.requestTimeoutMs() == null ? fallback.requestTimeoutMs() : request.requestTimeoutMs();
        int retryCount = request.retryCount() == null ? fallback.retryCount() : request.retryCount();
        long dailyTokenQuota = request.dailyTokenQuota() == null ? fallback.dailyTokenQuota() : request.dailyTokenQuota();
        boolean enabled = request.enabled() == null ? fallback.enabled() : request.enabled();
        if (!Double.isFinite(temperature)
            || temperature < 0.0
            || temperature > 2.0
            || maxOutputTokens < 1
            || maxOutputTokens > 32_768
            || requestTimeoutMs < 1_000
            || requestTimeoutMs > 120_000
            || retryCount < 0
            || retryCount > 5
            || dailyTokenQuota < 0
            || dailyTokenQuota > 10_000_000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_CONFIG_SETTINGS_INVALID", "模型运行参数无效");
        }
        if (enabled && dailyTokenQuota == 0) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "MODEL_CONFIG_QUOTA_REQUIRED",
                "启用模型配置前必须设置单用户每日令牌配额"
            );
        }
        return new ModelConfigGenerationControls(
            temperature,
            maxOutputTokens,
            requestTimeoutMs,
            retryCount,
            dailyTokenQuota,
            enabled
        );
    }

    private String connectionCode(ModelConfigConnectionResult result) {
        if (result == null || result.code() == null || !result.code().matches("[A-Z0-9_]{1,64}")) {
            return "CONNECTION_FAILED";
        }
        return result.code();
    }

    private void appendAudit(
        AuthenticatedUser actor,
        String action,
        String result,
        String requestId,
        ModelConfigRepository.StoredModelConfig before,
        ModelConfigRepository.StoredModelConfig after
    ) {
        repository.appendAuditEvent(
            actor.userId(),
            action,
            result,
            safeRequestId(requestId),
            summary(before),
            summary(after)
        );
    }

    private String safeRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return "";
        }
        return requestId.substring(0, Math.min(requestId.length(), 128));
    }

    private String summary(ModelConfigRepository.StoredModelConfig stored) {
        if (stored == null) {
            return "state=UNCONFIGURED";
        }
        return "configured=true;enabled=" + stored.enabled()
            + ";temperature=" + stored.temperature()
            + ";maxOutputTokens=" + stored.maxOutputTokens()
            + ";requestTimeoutMs=" + stored.requestTimeoutMs()
            + ";retryCount=" + stored.retryCount()
            + ";dailyTokenQuota=" + stored.dailyTokenQuota()
            + ";lastConnectionTestStatus=" + (stored.lastConnectionTestStatus() == null
                ? "NONE"
                : stored.lastConnectionTestStatus());
    }

    private ModelConfigController.ModelConfigView view(ModelConfigRepository.StoredModelConfig stored) {
        return new ModelConfigController.ModelConfigView(
            stored.provider(),
            stored.baseUrl(),
            stored.model(),
            true,
            stored.temperature(),
            stored.maxOutputTokens(),
            stored.requestTimeoutMs(),
            stored.retryCount(),
            stored.dailyTokenQuota(),
            stored.enabled(),
            stored.lastConnectionTestStatus(),
            stored.lastConnectionTestedAt(),
            stored.updatedAt()
        );
    }

    public record CapabilityState(boolean available, String reason) {
    }
}
