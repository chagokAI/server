package com.skala.chagok.dlanalysis;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * DL 측정 결과를 한 줄짜리 JSON(JSON Lines)으로 파일에 남긴다.
 *
 * <p>item_event에는 치수·응답 시간이 없으므로, 측정 결과를 따로 모아 분석에 넘길 때 이 파일을 쓴다.
 * chagok.dl.measure.measurement-log에 경로를 줄 때만 동작하고, 없으면 아무것도 하지 않는다.
 * 기록 실패가 이미 끝난 업무 반영을 실패로 보이게 하면 안 되므로, 쓰기 오류는 예외로 올리지 않고 경고 로그만 남긴다.
 */
@Slf4j
@Component
class DlMeasurementLog {

    private final Path path;
    private final JsonMapper mapper = JsonMapper.builder().build();

    DlMeasurementLog(DlMeasureClientProperties properties) {
        this.path = properties.measurementLog();
        if (path != null && path.getParent() != null) {
            try {
                Files.createDirectories(path.getParent());
            } catch (IOException e) {
                throw new UncheckedIOException("측정 결과 기록 폴더를 만들 수 없다: " + path, e);
            }
        }
    }

    /**
     * @param result DL 응답. DL이 거부했거나 호출하지 못했으면 null
     * @param latencyMillis DL 호출 소요 시간. 호출하지 못했으면 null
     */
    synchronized void write(String eventId, DlMeasureResult result, Long latencyMillis, DlAnalysisOutcome outcome) {
        if (path == null) {
            return;
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("eventId", eventId);
        line.put("measuredAt", Instant.now().toString());
        line.put("latencyMs", latencyMillis);
        line.put("dlStatus", result == null ? null : result.status());
        line.put("barcode", result == null ? null : result.barcode());
        DlMeasureResult.Size size = result == null ? null : result.size();
        line.put("sizeType", size == null ? null : size.type());
        line.put("widthCm", size == null ? null : size.widthCm());
        line.put("depthCm", size == null ? null : size.depthCm());
        line.put("heightCm", size == null ? null : size.heightCm());
        line.put("volumeCm3", size == null ? null : size.volumeCm3());
        line.put("outcome", outcome.status() == null ? "SKIPPED" : outcome.status().name());
        line.put("reason", outcome.reason() == null ? null : outcome.reason().name());
        try {
            Files.writeString(path, mapper.writeValueAsString(line) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("측정 결과 기록 실패: eventId={}, path={}, cause={}", eventId, path, e.getMessage());
        }
    }
}
