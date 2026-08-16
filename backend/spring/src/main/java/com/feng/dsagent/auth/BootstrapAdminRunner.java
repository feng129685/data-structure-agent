package com.feng.dsagent.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Runs only when BOOTSTRAP_ADMIN_PROVISION_ENABLED=true is set for a controlled deployment. */
@Component
final class BootstrapAdminRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

    private final BootstrapAdminProperties properties;
    private final AdminAccountProvisioner provisioner;

    BootstrapAdminRunner(BootstrapAdminProperties properties, AdminAccountProvisioner provisioner) {
        this.properties = properties;
        this.provisioner = provisioner;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.enabled()) {
            provisioner.provisionAdministrator(
                properties.email(),
                properties.username(),
                properties.password(),
                properties.reconcileExisting()
            );
            log.info(
                "Controlled bootstrap administrator provisioning completed; reconciliation={}",
                properties.reconcileExisting()
            );
        }
    }
}
