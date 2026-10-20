package com.skala.chagok.dlanalysis;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 목록 파일의 촬영분을 일괄로 접수한다. 시계열 분석용 측정 데이터를 실제 파이프라인으로 만들 때 쓴다.
 *
 * <p>chagok.dl.batch.manifest를 줄 때만 기동 시 한 번 돈다. 일반 실행에는 영향이 없다.
 * 목록은 탭 구분이다(폴더 이름에 쉼표가 있어 CSV는 따옴표 처리가 필요하다).
 * 열: run, occurred_at(ISO 8601), bundle_id, expected_barcode, cam1, cam2, cam3.
 *
 * <p>접수할 때마다 accepted 파일에 eventId와 목록 행을 잇는 줄을 남긴다. 측정 결과 기록은 eventId만 갖고 있어
 * 나중에 정답 바코드·회차와 합치려면 이 대응표가 필요하다. 발행이 실패한 행은 PUBLISH_FAILED로 남기고 계속한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "chagok.dl.batch", name = "manifest")
@EnableConfigurationProperties(DlAnalysisBatchAccepter.Properties.class)
@KafkaIntegrationEnabled
class DlAnalysisBatchAccepter implements ApplicationRunner {

    private static final String ACCEPTED_HEADER = "event_id\trun\toccurred_at\tbundle_id\texpected_barcode\taccept_status";

    private final DlAnalysisAcceptService acceptService;
    private final Properties properties;

    DlAnalysisBatchAccepter(DlAnalysisAcceptService acceptService, Properties properties) {
        this.acceptService = acceptService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        List<String> rows = Files.readAllLines(properties.manifest(), StandardCharsets.UTF_8);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("목록 파일이 비어 있다: " + properties.manifest());
        }
        int accepted = 0;
        int failed = 0;
        try (BufferedWriter out = Files.newBufferedWriter(properties.accepted(), StandardCharsets.UTF_8)) {
            out.write(ACCEPTED_HEADER);
            out.newLine();
            for (String row : rows.subList(1, rows.size())) {
                String[] f = row.split("\t", -1);
                String run = f[0];
                Instant occurredAt = Instant.parse(f[1]);
                String bundleId = f[2];
                String expectedBarcode = f[3];
                String eventId = "";
                String status;
                try {
                    eventId = acceptService.accept(properties.warehouseId(), List.of(f[4], f[5], f[6]), occurredAt);
                    status = "ACCEPTED";
                    accepted++;
                } catch (DlAnalysisRequestPublishException e) {
                    status = "PUBLISH_FAILED";
                    failed++;
                    log.warn("일괄 접수 발행 실패: bundleId={}, cause={}", bundleId, e.getMessage());
                }
                out.write(String.join("\t", eventId, run, occurredAt.toString(), bundleId, expectedBarcode, status));
                out.newLine();
                if ((accepted + failed) % 1000 == 0) {
                    out.flush();
                    log.info("일괄 접수 진행: {}/{}", accepted + failed, rows.size() - 1);
                }
            }
        }
        log.info("일괄 접수 완료: accepted={}, publishFailed={}, manifest={}", accepted, failed, properties.manifest());
    }

    /**
     * @param manifest 접수할 목록(탭 구분)
     * @param accepted eventId 대응표를 쓸 경로
     * @param warehouseId 접수할 센터
     */
    @ConfigurationProperties("chagok.dl.batch")
    record Properties(Path manifest, Path accepted, UUID warehouseId) {
    }
}
