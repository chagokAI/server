package com.skala.chagok.dlanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class DlAnalysisRequestTest {

    private static final Instant CAPTURED_AT = Instant.parse("2026-10-20T06:00:00Z");

    @Test
    void ofSetsCurrentSchemaVersion() {
        DlAnalysisRequest request = DlAnalysisRequest.of("evt-1", "job-1", "wh-1", "sh-1", "captures/job-1/cam1.jpg", CAPTURED_AT);

        assertThat(request.schemaVersion()).isEqualTo(DlAnalysisRequest.SCHEMA_VERSION);
    }

    @Test
    void rejectsBlankIdentifier() {
        assertThatThrownBy(() -> DlAnalysisRequest.of("evt-1", " ", "wh-1", "sh-1", "captures/job-1/cam1.jpg", CAPTURED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jobId");
    }

    @Test
    void rejectsMissingImageRef() {
        assertThatThrownBy(() -> DlAnalysisRequest.of("evt-1", "job-1", "wh-1", "sh-1", null, CAPTURED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("imageRef");
    }

    @Test
    void rejectsMissingCapturedAtWithSameExceptionTypeAsOtherRequiredFields() {
        assertThatThrownBy(() -> DlAnalysisRequest.of("evt-1", "job-1", "wh-1", "sh-1", "captures/job-1/cam1.jpg", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capturedAt");
    }

    @Test
    void rejectsUnsupportedSchemaVersion() {
        assertThatThrownBy(() -> new DlAnalysisRequest(2, "evt-1", "job-1", "wh-1", "sh-1", "captures/job-1/cam1.jpg", CAPTURED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schemaVersion");
    }
}
