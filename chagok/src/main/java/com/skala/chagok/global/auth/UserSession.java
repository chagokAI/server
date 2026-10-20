package com.skala.chagok.global.auth;
import com.skala.chagok.domain.user.entity.AppUser;
import java.util.*;

public record UserSession(UUID id, String employeeId, String name, String department, String jobTitle,
                          String role, WarehouseInfo warehouse, List<WarehouseInfo> warehouses) {
    public record WarehouseInfo(UUID id, String name) {}
    public static UserSession from(AppUser user) {
        var w = user.getWarehouse();
        var info = w == null ? null : new WarehouseInfo(w.getId(), w.getName());
        return new UserSession(user.getId(), user.getEmployeeNo(), user.getName(), user.getDepartment(),
                user.getJobTitle(), user.getRole(), info, info == null ? List.of() : List.of(info));
    }
}
