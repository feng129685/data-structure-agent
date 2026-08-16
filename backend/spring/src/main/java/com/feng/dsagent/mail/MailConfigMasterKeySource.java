package com.feng.dsagent.mail;

import java.util.Optional;
import javax.crypto.SecretKey;

interface MailConfigMasterKeySource {
    Optional<SecretKey> masterKey();
}
