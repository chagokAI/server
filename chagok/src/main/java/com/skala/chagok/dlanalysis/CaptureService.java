package com.skala.chagok.dlanalysis;

import com.skala.chagok.domain.dashboard.dto.SceneResponse;
import com.skala.chagok.domain.event.repository.ItemEventRepository;
import com.skala.chagok.global.error.BusinessException;
import com.skala.chagok.global.web.WarehouseQueryGuard;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service @RequiredArgsConstructor @KafkaIntegrationEnabled
public class CaptureService {
    private final CaptureImageStore images;
    private final DlAnalysisAcceptService accept;
    private final ItemEventRepository events;
    private final WarehouseQueryGuard warehouses;

    public record Accepted(String eventId, String status, int imageCount) {}
    public record Result(String eventId, String status, Instant occurredAt, int imageCount,
                         String failureReason, String failureMessage, SceneResponse.Observation observation) {}

    public Accepted upload(UUID warehouseId, List<MultipartFile> files) {
        warehouses.require(warehouseId);
        List<String> refs = images.save(warehouseId, files);
        try {
            return new Accepted(accept.accept(warehouseId, refs), "PENDING", refs.size());
        } catch (RuntimeException error) {
            images.discard(refs);
            throw error;
        }
    }

    @Transactional(readOnly = true)
    public Result get(UUID warehouseId, String eventId) {
        warehouses.require(warehouseId);
        var event = events.findByWarehouseIdAndId_EventId(warehouseId, eventId)
                .orElseThrow(() -> BusinessException.notFound("촬영 작업"));
        return new Result(eventId, event.getStatus(), event.getId().getOccurredAt(),
                event.getImageRefs() == null ? 0 : event.getImageRefs().size(),
                event.getFailureReason(), event.getFailureMessage(), SceneResponse.Observation.from(event));
    }
}
