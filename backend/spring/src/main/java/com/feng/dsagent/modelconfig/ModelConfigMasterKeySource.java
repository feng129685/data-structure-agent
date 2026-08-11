package com.feng.dsagent.modelconfig;

import java.util.Optional;
import javax.crypto.SecretKey;

interface ModelConfigMasterKeySource {

    Optional<SecretKey> masterKey();
}
