package za.co.oip.security;

import org.junit.jupiter.api.Test;
import za.co.oip.common.error.Exceptions.BusinessRuleException;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FieldEncryptorTest {

    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void roundTripsWithAFreshIvEachTime() {
        FieldEncryptor e = new FieldEncryptor(KEY);
        String a = e.encrypt("8001015009087");
        String b = e.encrypt("8001015009087");
        assertThat(a).startsWith("v1:").isNotEqualTo(b);
        assertThat(e.decrypt(a)).isEqualTo("8001015009087");
    }

    @Test
    void detectsTamperingAndWrongKeys() {
        FieldEncryptor e = new FieldEncryptor(KEY);
        String c = e.encrypt("8001015009087");
        char last = c.charAt(c.length() - 2);
        String tampered = c.substring(0, c.length() - 2) + (last == 'A' ? 'B' : 'A') + c.charAt(c.length() - 1);
        assertThatThrownBy(() -> e.decrypt(tampered)).isInstanceOf(IllegalStateException.class);

        byte[] other = new byte[32];
        other[0] = 1;
        assertThatThrownBy(() -> new FieldEncryptor(Base64.getEncoder().encodeToString(other)).decrypt(c))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void withoutAKeyItRefusesClearlyAndBadKeysFailAtStartup() {
        assertThatThrownBy(() -> new FieldEncryptor("").encrypt("x")).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("OIP_DATA_ENCRYPTION_KEY");
        assertThatThrownBy(() -> new FieldEncryptor(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class);
    }
}
