package com.skala.chagok.query;

import com.skala.chagok.domain.box.repository.BoxSpecRepository;
import com.skala.chagok.domain.dashboard.dto.SceneResponse;
import com.skala.chagok.domain.dashboard.repository.MetricQueryRepository;
import com.skala.chagok.domain.dashboard.repository.SimulationMeasurementRepository;
import com.skala.chagok.domain.dashboard.service.ObservationQueryService;
import com.skala.chagok.domain.event.entity.ItemEvent;
import com.skala.chagok.domain.event.entity.ItemEventId;
import com.skala.chagok.domain.event.repository.ItemEventRepository;
import com.skala.chagok.global.config.ChagokProperties;
import com.skala.chagok.global.web.WarehouseQueryGuard;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SceneMeasurementTests {
    private final Instant observedAt = Instant.parse("2026-10-23T01:00:00Z");
    private final Instant measuredAt = Instant.parse("2026-10-23T01:00:02Z");
    private final Map<String, Object> workerMeasurement = Map.of(
            "dlStatus", "known", "sizeType", "measured", "widthMm", 240,
            "depthMm", 180, "heightMm", 120, "latencyMs", 425,
            "measuredAt", "2026-10-23T01:00:02Z");

    @Test void observationKeepsWorkerMeasurementAndDoesNotInventPlacement() {
        var observation = SceneResponse.Observation.from(event(workerMeasurement));

        assertThat(observation.eventId()).isEqualTo("scene-event");
        assertThat(observation.measurement().widthMm()).isEqualTo(240);
        assertThat(observation.measurement().latencyMs()).isEqualTo(425L);
        assertThat(observation.measurement().measuredAt()).isEqualTo(measuredAt);
        assertThat(observation.measurement().source()).isEqualTo("WORKER");
        assertThat(observation.dimensionsSource()).isEqualTo("DL_MEASURED");
        assertThat(observation.placementAvailable()).isFalse();
        assertThat(observation.placement()).isNull();
    }

    @Test void observationWithoutMeasurementKeepsCatalogSource() {
        var observation = SceneResponse.Observation.from(event(null));

        assertThat(observation.measurement()).isNull();
        assertThat(observation.dimensionsSource()).isEqualTo("CATALOG");
    }

    @Test void sceneUsesImportedSampleWhenEventHasNoWorkerMeasurement() {
        var observation = scene(event(null)).items().getFirst();

        assertThat(observation.measurement().widthMm()).isEqualTo(999);
        assertThat(observation.measurement().source()).isEqualTo("IMPORTED_SAMPLE");
        assertThat(observation.dimensionsSource()).isEqualTo("DL_ESTIMATED");
    }

    @Test void importedSampleDoesNotOverwriteNewWorkerMeasurement() {
        var observation = scene(event(workerMeasurement)).items().getFirst();

        assertThat(observation.measurement().widthMm()).isEqualTo(240);
        assertThat(observation.measurement().source()).isEqualTo("WORKER");
        assertThat(observation.measurement().measuredAt()).isEqualTo(measuredAt);
    }

    private ItemEvent event(Map<String, Object> measurement) {
        return ItemEvent.builder().id(new ItemEventId("scene-event", observedAt))
                .status("SUCCEEDED").measurement(measurement).build();
    }

    private SceneResponse scene(ItemEvent event) {
        UUID warehouseId = UUID.randomUUID();
        var events = mock(ItemEventRepository.class);
        var measurements = mock(SimulationMeasurementRepository.class);
        when(events.findRecent(warehouseId, PageRequest.of(0, 100))).thenReturn(List.of(event));
        when(measurements.findAll(List.of("scene-event"))).thenReturn(Map.of("scene-event",
                new SceneResponse.Measurement("known", "estimated", 999, 800, 700, 100L,
                        observedAt, null, "IMPORTED_SAMPLE")));
        var properties = new ChagokProperties(ZoneId.of("Asia/Seoul"), 300,
                new ChagokProperties.Inventory(3, 14), new ChagokProperties.BoxSize(10, 50));
        var service = new ObservationQueryService(events, mock(MetricQueryRepository.class),
                mock(BoxSpecRepository.class), mock(WarehouseQueryGuard.class), properties, measurements);
        return service.scene(warehouseId, 100);
    }
}
