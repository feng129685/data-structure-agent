package com.feng.dsagent.modelconfig;

import com.feng.dsagent.model.ModelClient;
import com.feng.dsagent.model.ModelClientException;
import com.feng.dsagent.model.ModelErrorCode;
import com.feng.dsagent.model.ModelRequest;
import com.feng.dsagent.model.ModelResponse;
import com.feng.dsagent.model.ModelStreamHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
class PersistedModelConfigClient implements ModelClient {

    private final ModelClient environmentClient;
    private final ModelConfigRuntimeSettingsSource settings;
    private final ModelConfigRuntimeClientFactory configuredClientFactory;

    PersistedModelConfigClient(
        @Qualifier("openAiCompatibleModelClient") ModelClient environmentClient,
        ModelConfigRuntimeSettingsSource settings,
        ModelConfigRuntimeClientFactory configuredClientFactory
    ) {
        this.environmentClient = environmentClient;
        this.settings = settings;
        this.configuredClientFactory = configuredClientFactory;
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        return selectedClient().complete(request);
    }

    @Override
    public void stream(ModelRequest request, ModelStreamHandler handler) {
        selectedClient().stream(request, handler);
    }

    private ModelClient selectedClient() {
        try {
            return settings.current().map(configuredClientFactory::create).orElse(environmentClient);
        } catch (ModelConfigRuntimeUnavailableException error) {
            throw new ModelClientException(ModelErrorCode.MODEL_NOT_CONFIGURED);
        }
    }
}
