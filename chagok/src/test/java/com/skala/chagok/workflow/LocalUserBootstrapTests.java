package com.skala.chagok.workflow;

import com.skala.chagok.domain.user.entity.AppUser;
import com.skala.chagok.domain.user.repository.AppUserRepository;
import com.skala.chagok.domain.warehouse.entity.Warehouse;
import com.skala.chagok.domain.warehouse.repository.WarehouseRepository;
import com.skala.chagok.global.auth.LocalUserBootstrap;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LocalUserBootstrapTests {
    final AppUserRepository users = mock(AppUserRepository.class);
    final WarehouseRepository warehouses = mock(WarehouseRepository.class);
    final PasswordEncoder passwords = mock(PasswordEncoder.class);
    LocalUserBootstrap bootstrap(String password) {
        var runner = new LocalUserBootstrap(users, warehouses, passwords);
        ReflectionTestUtils.setField(runner, "employeeId", "operator-1");
        ReflectionTestUtils.setField(runner, "password", password);
        ReflectionTestUtils.setField(runner, "userName", "운영자");
        ReflectionTestUtils.setField(runner, "warehouseName", "로컬 센터");
        return runner;
    }
    @Test void existingCredentialsAreNeverOverwritten() {
        when(users.findByEmployeeNo("operator-1")).thenReturn(List.of(AppUser.builder().build()));
        bootstrap("different-password").run(null);
        verify(users, never()).saveAndFlush(any()); verifyNoInteractions(passwords, warehouses);
    }
    @Test void missingPasswordCannotCreateAnAccount() {
        assertThatThrownBy(() -> bootstrap("").run(null)).isInstanceOf(IllegalStateException.class);
        verify(users, never()).saveAndFlush(any()); verifyNoInteractions(warehouses);
    }
    @Test void onlyThePasswordHashIsStoredForTheFirstOperator() {
        when(warehouses.save(any())).thenReturn(Warehouse.builder().id(UUID.randomUUID()).build());
        when(passwords.encode("valid-password")).thenReturn("encoded-password");
        bootstrap("valid-password").run(null);
        verify(users).saveAndFlush(argThat(user -> user.getPasswordHash().equals("encoded-password") && user.getRole().equals("OPERATOR")));
    }
}
