package com.feng.dsagent.auth;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class BootstrapAdminRunnerTest {

    @Test
    void doesNothingWhenProvisioningIsDisabledEvenIfReconciliationIsConfigured() throws Exception {
        AdminAccountProvisioner provisioner = mock(AdminAccountProvisioner.class);
        BootstrapAdminRunner runner = new BootstrapAdminRunner(
            new BootstrapAdminProperties(false, true, "admin@example.com", "ACha_", "test-password"),
            provisioner
        );

        runner.run(new DefaultApplicationArguments());

        verifyNoInteractions(provisioner);
    }

    @Test
    void passesTheExplicitReconciliationFlagToTheProvisioner() throws Exception {
        AdminAccountProvisioner provisioner = mock(AdminAccountProvisioner.class);
        BootstrapAdminRunner runner = new BootstrapAdminRunner(
            new BootstrapAdminProperties(true, true, "admin@example.com", "ACha_", "test-password"),
            provisioner
        );

        runner.run(new DefaultApplicationArguments());

        verify(provisioner).provisionAdministrator("admin@example.com", "ACha_", "test-password", true);
    }

    @Test
    void preservesCreateOnlyModeWhenReconciliationIsDisabled() throws Exception {
        AdminAccountProvisioner provisioner = mock(AdminAccountProvisioner.class);
        BootstrapAdminRunner runner = new BootstrapAdminRunner(
            new BootstrapAdminProperties(true, false, "admin@example.com", "ACha_", "test-password"),
            provisioner
        );

        runner.run(new DefaultApplicationArguments());

        verify(provisioner).provisionAdministrator("admin@example.com", "ACha_", "test-password", false);
    }
}
