package com.skala.chagok.dlanalysis;

import java.time.Instant;

/**
 * DL 분석 요청 토픽에 싣는 메시지(가안, schemaVersion 1).
 *
 * <p>필드 이름은 팀 API의 JSON 관례(camelCase)를 따른다. 소비자가 A안의 Python Worker일 수도,
 * B안의 Spring 소비 모듈일 수도 있으므로 Java 타입에 기대지 않는 평범한 JSON 객체로 직렬화된다.
 *
 * <p>모든 필드가 필수다. 촬영 시각이나 이미지 참조가 없는 요청을 기본값으로 채워 보내면
 * 소비자는 그것을 실제 촬영 정보로 받아들이므로, 발행 전에 생성 단계에서 거부한다.
 *
 * @param schemaVersion 메시지 형식 버전. 본문만 남은 로그·재처리에서도 형식을 판별하기 위해 둔다
 * @param eventId 이 요청 메시지의 식별자. 같은 요청을 재전송할 때는 같은 값을 유지해 소비자가 중복을 가려낸다
 * @param jobId Spring이 소유한 분석 작업 ID. 메시지 키이자 결과를 되돌릴 기준이다
 * @param warehouseId 센터 ID. 소비자 측 권한 근거로 쓰지 않는다
 * @param shipperId 화주 ID. 소비자 측 권한 근거로 쓰지 않는다
 * @param imageRef 분석할 이미지의 저장소 참조. 이미지 원본은 메시지에 넣지 않는다
 * @param capturedAt 촬영 시각(UTC)
 */
public record DlAnalysisRequest(
        int schemaVersion,
        String eventId,
        String jobId,
        String warehouseId,
        String shipperId,
        String imageRef,
        Instant capturedAt) {

    public static final int SCHEMA_VERSION = 1;

    public DlAnalysisRequest {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("지원하지 않는 schemaVersion: " + schemaVersion);
        }
        requireText(eventId, "eventId");
        requireText(jobId, "jobId");
        requireText(warehouseId, "warehouseId");
        requireText(shipperId, "shipperId");
        requireText(imageRef, "imageRef");
        // 촬영 시각만 Objects.requireNonNull로 검사해 NullPointerException이 나가고, 나머지 필수 필드는
        // IllegalArgumentException이 나갔다. 호출자가 입력 오류를 한 예외 타입으로 받아 처리하도록 같은 타입으로 맞췄다.
        if (capturedAt == null) {
            throw new IllegalArgumentException("capturedAt는 비어 있을 수 없다");
        }
    }

    public static DlAnalysisRequest of(
            String eventId,
            String jobId,
            String warehouseId,
            String shipperId,
            String imageRef,
            Instant capturedAt) {
        return new DlAnalysisRequest(SCHEMA_VERSION, eventId, jobId, warehouseId, shipperId, imageRef, capturedAt);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "는 비어 있을 수 없다");
        }
    }
}
