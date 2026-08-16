package com.feng.dsagent.auth;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Runs only when BOOTSTRAP_ADMIN_PROVISION_ENABLED=true is set for a controlled deployment. */
@Component
final class BootstrapAdminRunner implements ApplicationRunner {

    private final BootstrapAdminProperties properties;
    private final AdminAccountProvisioner provisioner;

    BootstrapAdminRunner(BootstrapAdminProperties properties, AdminAccountProvisioner provisioner) {
        this.properties = properties;
        this.provisioner = provisioner;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.enabled()) {
            provisioner.createAdministrator(properties.email(), properties.username(), properties.password());
        }
    }
}
