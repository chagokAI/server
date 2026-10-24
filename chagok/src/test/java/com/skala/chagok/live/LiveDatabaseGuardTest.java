package com.skala.chagok.live;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LiveDatabaseGuardTest {
    @org.junit.jupiter.api.Test
    void postgresDriverConfirmsQueryHostOverridesWithoutConnecting() throws Exception {
        String url = "jdbc:postgresql://127.0.0.1/chagok?PGHOST=remote";
        var properties = java.sql.DriverManager.getDriver(url).getPropertyInfo(url, new java.util.Properties());
        assertThat(java.util.Arrays.stream(properties).filter(property -> "PGHOST".equals(property.name))
                .findFirst().orElseThrow().value).isEqualTo("remote");
        assertThatThrownBy(() -> LiveDatabaseGuard.requireAllowed(url, false, "test")).isInstanceOf(IllegalStateException.class);
    }
    @ParameterizedTest
    @ValueSource(strings = {"jdbc:postgresql://localhost:55433/chagok", "jdbc:postgresql://127.0.0.1/chagok",
            "jdbc:postgresql://[::1]:5432/chagok"})
    void allowsOnlyExplicitLoopback(String url) {
        assertThatCode(() -> LiveDatabaseGuard.requireAllowed(url, false, "test")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:postgresql://mac-mini/chagok", "jdbc:postgresql://100.1.2.3/chagok",
            "jdbc:postgresql://localhost.evil/chagok", "jdbc:postgresql://localhost,remote/chagok",
            "jdbc:postgresql:chagok", "invalid", "jdbc:mysql://localhost/chagok"})
    void rejectsRemoteOrAmbiguousUrls(String url) {
        assertThatThrownBy(() -> LiveDatabaseGuard.requireAllowed(url, false, "test"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("test");
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:postgresql://127.0.0.1/chagok?PGHOST=remote",
            "jdbc:postgresql://localhost/chagok?host=remote", "jdbc:postgresql://localhost/chagok?%50GHOST=remote",
            "jdbc:postgresql://localhost/chagok?PGHOST=localhost,remote", "jdbc:postgresql://localhost/chagok?service=shared",
            "jdbc:postgresql://localhost/chagok?socketFactory=custom.Factory"})
    void rejectsConnectionOverridesEvenWhenTheAuthorityIsLocal(String url) {
        assertThatThrownBy(() -> LiveDatabaseGuard.requireAllowed(url, false, "test")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> LiveDatabaseGuard.requireAllowed(url, true, "test")).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:postgresql://mac-mini:15432/chagok", "jdbc:postgresql://100.1.2.3/chagok"})
    void allowsRemoteOnlyAfterExplicitOptIn(String url) {
        assertThatCode(() -> LiveDatabaseGuard.requireAllowed(url, true, "test")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:postgresql://localhost,remote/chagok", "jdbc:postgresql://user@remote/chagok",
            "jdbc:postgresql://remote:0/chagok", "jdbc:postgresql://remote:99999/chagok", "jdbc:mysql://remote/chagok", "invalid"})
    void rejectsAmbiguousTargetsEvenWithRemoteOptIn(String url) {
        assertThatThrownBy(() -> LiveDatabaseGuard.requireAllowed(url, true, "test")).isInstanceOf(IllegalStateException.class);
    }
}
