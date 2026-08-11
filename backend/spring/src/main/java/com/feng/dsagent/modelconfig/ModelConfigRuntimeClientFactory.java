package com.feng.dsagent.modelconfig;

import com.feng.dsagent.model.ModelClient;

@FunctionalInterface
interface ModelConfigRuntimeClientFactory {

    ModelClient create(ModelConfigRuntimeSettings settings);
}
