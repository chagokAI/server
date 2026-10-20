package com.skala.chagok.domain.event.repository;

import com.skala.chagok.domain.event.entity.ItemEvent;
import com.skala.chagok.domain.event.entity.ItemEventId;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemEventRepository extends JpaRepository<ItemEvent, ItemEventId> {

    boolean existsByBoxSpecId(UUID boxSpecId);
}
