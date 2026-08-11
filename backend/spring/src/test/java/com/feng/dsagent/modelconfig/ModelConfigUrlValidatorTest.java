package com.feng.dsagent.modelconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.feng.dsagent.common.ApiException;
import java.net.InetAddress;
import java.net.URI;
import org.junit.jupiter.api.Test;

class ModelConfigUrlValidatorTest {

    @Test
    void rejectsNonHttpsUserInfoAndLocalHostnamesBeforeResolvingThem() {
        ModelConfigUrlValidator validator = validatorFor(ip(1, 1, 1, 1));

        assertUnsafe(validator, "http://model.example/v1");
        assertUnsafe(validator, "https://user@model.example/v1");
        assertUnsafe(validator, "https://localhost/v1");
        assertUnsafe(validator, "https://metadata.google.internal/v1");
    }

    @Test
    void rejectsPrivateLinkLocalAndCloudMetadataAddressesReturnedByDns() {
        assertUnsafe(validatorFor(ip(10, 0, 0, 7)), "https://model.example/v1");
        assertUnsafe(validatorFor(ip(169, 254, 169, 254)), "https://model.example/v1");
        assertUnsafe(validatorFor(ip(100, 100, 100, 200)), "https://model.example/v1");
    }

    @Test
    void acceptsOnlyAHostWhoseResolvedAddressesArePublic() {
        ModelConfigUrlValidator validator = validatorFor(ip(1, 1, 1, 1));

        assertThat(validator.validate("https://model.example/v1")).isEqualTo(URI.create("https://model.example/v1"));
    }

    @Test
    void returnsTheValidatedAddressesThatTheConnectionProbeMustPin() {
        InetAddress address = ip(1, 1, 1, 1);
        ModelConfigResolvedTarget target = validatorFor(address).resolve("https://model.example/v1");

        assertThat(target.uri()).isEqualTo(URI.create("https://model.example/v1"));
        assertThat(target.host()).isEqualTo("model.example");
        assertThat(target.port()).isEqualTo(443);
        assertThat(target.addresses()).containsExactly(address);
    }

    private ModelConfigUrlValidator validatorFor(InetAddress... addresses) {
        return new ModelConfigUrlValidator(host -> addresses);
    }

    private void assertUnsafe(ModelConfigUrlValidator validator, String baseUrl) {
        ApiException exception = assertThrows(ApiException.class, () -> validator.validate(baseUrl));
        assertThat(exception.code()).isEqualTo("MODEL_CONFIG_URL_UNSAFE");
    }

    private InetAddress ip(int first, int second, int third, int fourth) {
        try {
            return InetAddress.getByAddress(new byte[] {
                (byte) first,
                (byte) second,
                (byte) third,
                (byte) fourth
            });
        } catch (java.net.UnknownHostException error) {
            throw new AssertionError(error);
        }
    }
}
