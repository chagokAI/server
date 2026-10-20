package com.skala.chagok.global.auth;

import com.skala.chagok.domain.user.entity.AppUser;
import com.skala.chagok.domain.user.repository.AppUserRepository;
import com.skala.chagok.domain.warehouse.entity.Warehouse;
import com.skala.chagok.domain.warehouse.repository.WarehouseRepository;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 로컬에서 명시적으로 활성화한 경우에만 최초 계정을 생성한다. 기존 계정은 변경하지 않는다. */
@Component @RequiredArgsConstructor @ConditionalOnProperty(name = "chagok.bootstrap.enabled", havingValue = "true")
public class LocalUserBootstrap implements ApplicationRunner {
    private final AppUserRepository users;
    private final WarehouseRepository warehouses;
    private final PasswordEncoder passwords;
    @Value("${chagok.bootstrap.employee-id:}") private String employeeId;
    @Value("${chagok.bootstrap.password:}") private String password;
    @Value("${chagok.bootstrap.user-name:초기 운영자}") private String userName;
    @Value("${chagok.bootstrap.warehouse-id:}") private String warehouseId;
    @Value("${chagok.bootstrap.warehouse-name:차곡 로컬 센터}") private String warehouseName;

    @Override @Transactional
    public void run(ApplicationArguments args) {
        if (employeeId == null || employeeId.isBlank() || employeeId.trim().length() > 30)
            throw new IllegalStateException("초기 계정 사번을 1~30자로 지정하세요.");
        if (!users.findByEmployeeNo(employeeId.trim()).isEmpty()) return;
        if (password == null || password.isBlank() || password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalStateException("초기 계정 비밀번호는 8자 이상, UTF-8 기준 72바이트 이하여야 합니다.");
        if (userName == null || userName.isBlank() || userName.length() > 50 || warehouseName == null || warehouseName.isBlank() || warehouseName.length() > 100)
            throw new IllegalStateException("초기 사용자명과 센터명을 확인하세요.");
        Warehouse warehouse = warehouseId == null || warehouseId.isBlank()
                ? warehouses.save(Warehouse.builder().name(warehouseName).build())
                : warehouses.lockById(UUID.fromString(warehouseId)).orElseThrow(() -> new IllegalStateException("초기 계정의 센터가 없습니다."));
        users.saveAndFlush(AppUser.builder().warehouse(warehouse).employeeNo(employeeId.trim()).name(userName)
                .passwordHash(passwords.encode(password)).role("OPERATOR").build());
    }
}
