package com.skala.chagok.dlanalysis;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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

    DlMeasurementLog(DlMeasurementLogProperties properties) {
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
     * 키 이름은 결과 토픽 도입 전 기록과 같다. 일괄 측정 결과를 합치는 스크립트가 이 키를 읽는다.
     * measuredAt·latencyMs는 Worker가 잰 값이라, Spring이 DL을 직접 부르던 때의 HTTP 호출 시간과는 범위가 다르다.
     *
     * @param result Worker가 보낸 결과. 메시지를 읽지 못했으면 null
     */
    synchronized void write(String eventId, DlAnalysisResult result, DlAnalysisOutcome outcome) {
        if (path == null) {
            return;
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("eventId", eventId);
        line.put("measuredAt", result == null ? null : result.measuredAt());
        line.put("latencyMs", result == null ? null : result.latencyMs());
        line.put("dlStatus", result == null ? null : result.dlStatus());
        line.put("barcode", result == null ? null : result.barcode());
        DlAnalysisResult.Size size = result == null ? null : result.size();
        line.put("sizeType", size == null ? null : size.type());
        line.put("widthCm", size == null ? null : size.widthCm());
        line.put("depthCm", size == null ? null : size.depthCm());
        line.put("heightCm", size == null ? null : size.heightCm());
        line.put("volumeCm3", size == null ? null : size.volumeCm3());
        line.put("outcome", outcome.status() == null ? "SKIPPED" : outcome.status().name());
        line.put("reason", outcome.reason() == null ? null : outcome.reason().name());
        line.put("failureMessage", result == null ? null : result.failureMessage());
        try {
            Files.writeString(path, mapper.writeValueAsString(line) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("측정 결과 기록 실패: eventId={}, path={}, cause={}", eventId, path, e.getMessage());
        }
    }
}
