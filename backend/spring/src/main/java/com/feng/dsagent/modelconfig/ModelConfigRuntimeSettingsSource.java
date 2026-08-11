package com.feng.dsagent.modelconfig;

import java.util.Optional;

@FunctionalInterface
interface ModelConfigRuntimeSettingsSource {

    Optional<ModelConfigRuntimeSettings> current();
}
