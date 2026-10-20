package com.skala.chagok.dlanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class DlAnalysisRequestTest {

    private static final List<String> IMAGES = List.of("captures/evt-1/cam1.jpg", "captures/evt-1/cam2.jpg", "captures/evt-1/cam3.jpg");

    @Test
    void ofSetsCurrentSchemaVersion() {
        DlAnalysisRequest request = DlAnalysisRequest.of("evt-1", "wh-1", IMAGES);

        assertThat(request.schemaVersion()).isEqualTo(DlAnalysisRequest.SCHEMA_VERSION);
    }

    @Test
    void rejectsBlankEventId() {
        assertThatThrownBy(() -> DlAnalysisRequest.of(" ", "wh-1", IMAGES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId");
    }

    @Test
    void rejectsMissingImageRefs() {
        assertThatThrownBy(() -> DlAnalysisRequest.of("evt-1", "wh-1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("imageRefs");
    }

    @Test
    void rejectsImageCountOtherThanThreeCameras() {
        assertThatThrownBy(() -> DlAnalysisRequest.of("evt-1", "wh-1", IMAGES.subList(0, 2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("imageRefs");
    }

    @Test
    void rejectsBlankImageRefWithSameExceptionTypeAsOtherRequiredFields() {
        assertThatThrownBy(() -> DlAnalysisRequest.of("evt-1", "wh-1", List.of("cam1.jpg", "", "cam3.jpg")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("imageRefs");
    }

    @Test
    void keepsImageRefsUnchangedWhenCallerListChangesLater() {
        List<String> images = new ArrayList<>(IMAGES);
        DlAnalysisRequest request = DlAnalysisRequest.of("evt-1", "wh-1", images);

        images.set(0, "changed.jpg");

        assertThat(request.imageRefs()).containsExactlyElementsOf(IMAGES);
    }

    @Test
    void rejectsUnsupportedSchemaVersion() {
        assertThatThrownBy(() -> new DlAnalysisRequest(2, "evt-1", "wh-1", IMAGES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schemaVersion");
    }
}
