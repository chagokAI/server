package com.skala.chagok.domain.purchase.dto.request;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.UUID;

public record DraftFromProposalRequest(@NotNull UUID proposalId, LocalDate requestedDeliveryDate) {}
