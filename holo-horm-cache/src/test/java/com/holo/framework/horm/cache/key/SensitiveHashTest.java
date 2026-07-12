package com.holo.framework.horm.cache.key;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SensitiveHash} covering digest length, determinism
 * and the bounds of the truncation parameter.
 */
class SensitiveHashTest {

    @Test
    void hashReturnsFull64CharHexDigest() {
        String hash = SensitiveHash.hash("password");

        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]{64}");
    }

    @Test
    void hashIsDeterministicForSameInput() {
        String a = SensitiveHash.hash("password");
        String b = SensitiveHash.hash("password");

        assertThat(a).isEqualTo(b);
    }

    @Test
    void hashDifferentiatesDifferentInputs() {
        String a = SensitiveHash.hash("password");
        String b = SensitiveHash.hash("password1");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void hashDoesNotLeakRawValueInDigest() {
        String hash = SensitiveHash.hash("super-secret-token");

        assertThat(hash).doesNotContain("super-secret-token");
    }

    @Test
    void hashAcceptsNonStringObjectsViaToString() {
        String fromLong = SensitiveHash.hash(42L);
        String fromString = SensitiveHash.hash("42");

        // Long.toString() and "42" produce the same digest.
        assertThat(fromLong).isEqualTo(fromString);
    }

    @Test
    void truncatedHashReturnsRequestedPrefixLength() {
        String full = SensitiveHash.hash("password");
        String truncated = SensitiveHash.hash("password", 16);

        assertThat(truncated).hasSize(16);
        assertThat(truncated).isEqualTo(full.substring(0, 16));
    }

    @Test
    void truncatedHashAtFullLengthEqualsFullHash() {
        String full = SensitiveHash.hash("password");
        String alsoFull = SensitiveHash.hash("password", 64);

        assertThat(alsoFull).isEqualTo(full);
    }

    @Test
    void truncatedHashAtLengthOneIsSingleHexChar() {
        String hash = SensitiveHash.hash("password", 1);

        assertThat(hash).hasSize(1);
        assertThat(hash).matches("[0-9a-f]");
    }

    @Test
    void truncatedHashRejectsZeroLength() {
        assertThatThrownBy(() -> SensitiveHash.hash("password", 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("truncateLength");
    }

    @Test
    void truncatedHashRejectsNegativeLength() {
        assertThatThrownBy(() -> SensitiveHash.hash("password", -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("truncateLength");
    }

    @Test
    void truncatedHashRejectsLengthAbove64() {
        assertThatThrownBy(() -> SensitiveHash.hash("password", 65))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("truncateLength");
    }

    @Test
    void hashRejectsNullValue() {
        assertThatThrownBy(() -> SensitiveHash.hash(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("value");
    }

    @Test
    void truncatedHashRejectsNullValue() {
        assertThatThrownBy(() -> SensitiveHash.hash(null, 16))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("value");
    }

    @Test
    void emptyStringProducesValidHash() {
        String hash = SensitiveHash.hash("");

        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]{64}");
    }
}
