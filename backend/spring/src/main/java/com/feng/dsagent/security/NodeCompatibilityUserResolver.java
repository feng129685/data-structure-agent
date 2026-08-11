package com.feng.dsagent.security;

import com.feng.dsagent.auth.UserAccount;
import com.feng.dsagent.auth.UserRepository;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class NodeCompatibilityUserResolver {

    private final UserRepository users;
    private final PasswordEncoder passwords;

    public NodeCompatibilityUserResolver(UserRepository users, PasswordEncoder passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    @Transactional
    public AuthenticatedUser resolve(NodeCompatibilityToken token) {
        String email = token.email().trim().toLowerCase(Locale.ROOT);
        UserAccount account = users.findByEmail(email).orElseGet(() -> createBridgeAccount(email));
        return new AuthenticatedUser(account.id(), account.email(), account.roles());
    }

    private UserAccount createBridgeAccount(String email) {
        try {
            // Node remains the password authority for this compatibility path.
            // The random hash makes the mirrored Spring account non-loginable
            // until an explicit, audited identity migration is introduced.
            // Environment bootstrap roles are never granted by implicit mirroring.
            return users.create(email, passwords.encode(UUID.randomUUID().toString()), Set.of("STUDENT"));
        } catch (DuplicateKeyException race) {
            return users.findByEmail(email)
                .orElseThrow(() -> new InvalidTokenException("Node compatibility identity is unavailable", race));
        }
    }
}
