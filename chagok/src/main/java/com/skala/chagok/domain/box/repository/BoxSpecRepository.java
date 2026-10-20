package com.skala.chagok.domain.box.repository;

import com.skala.chagok.domain.box.entity.BoxSpec;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BoxSpecRepository extends JpaRepository<BoxSpec, UUID> {
}
