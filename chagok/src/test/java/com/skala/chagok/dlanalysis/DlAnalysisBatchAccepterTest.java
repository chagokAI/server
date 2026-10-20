package com.skala.chagok.dlanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 목록의 각 행이 지정한 촬영 시각으로 접수되고, 발행 실패 행도 대응표에 남는지 확인한다.
 * 폴더 이름에 쉼표가 있어도 탭 구분이라 경로가 깨지지 않아야 한다.
 */
class DlAnalysisBatchAccepterTest {

    private static final UUID WAREHOUSE_ID = UUID.fromString("2b7424cf-37a9-5119-98d7-a23142cbb251");

    @TempDir
    Path dir;

    @Test
    void acceptsEachRowWithItsOccurredAtAndRecordsEventIds() throws Exception {
        Path manifest = dir.resolve("manifest.tsv");
        Files.writeString(manifest, String.join("\n",
                "run\toccurred_at\tbundle_id\texpected_barcode\tcam1\tcam2\tcam3",
                "1\t2026-05-01T00:10:00Z\tb-1\t8809734280014\t/a/05_의약품,의료기기/c1.jpg\t/a/c2.jpg\t/a/c3.jpg",
                "1\t2026-05-02T03:00:00Z\tb-2\t8801007129495\t/b/c1.jpg\t/b/c2.jpg\t/b/c3.jpg"), StandardCharsets.UTF_8);
        Path accepted = dir.resolve("accepted.tsv");
        DlAnalysisAcceptService acceptService = mock(DlAnalysisAcceptService.class);
        when(acceptService.accept(eq(WAREHOUSE_ID),
                eq(List.of("/a/05_의약품,의료기기/c1.jpg", "/a/c2.jpg", "/a/c3.jpg")),
                eq(Instant.parse("2026-05-01T00:10:00Z")))).thenReturn("evt-1");
        when(acceptService.accept(eq(WAREHOUSE_ID), eq(List.of("/b/c1.jpg", "/b/c2.jpg", "/b/c3.jpg")), any(Instant.class)))
                .thenThrow(new DlAnalysisRequestPublishException("evt-2", new RuntimeException("broker down")));

        new DlAnalysisBatchAccepter(acceptService, new DlAnalysisBatchAccepter.Properties(manifest, accepted, WAREHOUSE_ID))
                .run(null);

        assertThat(Files.readAllLines(accepted)).containsExactly(
                "event_id\trun\toccurred_at\tbundle_id\texpected_barcode\taccept_status",
                "evt-1\t1\t2026-05-01T00:10:00Z\tb-1\t8809734280014\tACCEPTED",
                "\t1\t2026-05-02T03:00:00Z\tb-2\t8801007129495\tPUBLISH_FAILED");
    }
}
