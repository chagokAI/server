package com.skala.chagok.global.auth;
import jakarta.validation.constraints.*;
public record LoginRequest(@NotBlank @Size(max = 30) String employeeId,
                           @NotBlank @Size(max = 200) String password, Boolean rememberMe) {}
