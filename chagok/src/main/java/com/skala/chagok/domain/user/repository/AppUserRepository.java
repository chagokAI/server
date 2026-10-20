package com.skala.chagok.domain.user.repository;

import com.skala.chagok.domain.user.entity.AppUser;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findFirstByOrderByEmployeeNoAsc();
}
