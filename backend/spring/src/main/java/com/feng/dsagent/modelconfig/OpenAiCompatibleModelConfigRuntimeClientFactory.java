package com.feng.dsagent.modelconfig;

import com.feng.dsagent.model.ModelClient;
import com.feng.dsagent.model.ModelProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class OpenAiCompatibleModelConfigRuntimeClientFactory implements ModelConfigRuntimeClientFactory {

    private final ModelProperties defaults;
    private final ObjectMapper objectMapper;
    private final PinnedHttpsTransport transport;

    OpenAiCompatibleModelConfigRuntimeClientFactory(
        ModelProperties defaults,
        ObjectMapper objectMapper,
        PinnedHttpsTransport transport
    ) {
        this.defaults = defaults;
        this.objectMapper = objectMapper;
        this.transport = transport;
    }

    @Override
    public ModelClient create(ModelConfigRuntimeSettings settings) {
        return new PinnedOpenAiCompatibleModelClient(settings, defaults, objectMapper, transport);
    }
}
