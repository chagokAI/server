package com.skala.chagok.dlanalysis;

import java.util.List;

/**
 * DL 분석 요청 토픽에 싣는 메시지(가안, schemaVersion 1).
 *
 * <p>분석 작업 1건은 item_event 1행이다. 그래서 작업 식별자는 item_event.event_id 하나를 쓰고,
 * 소비자는 이 값만으로 행을 찾는다. 필드 이름은 팀 API의 JSON 관례(camelCase)를 따르고,
 * Java 타입에 기대지 않는 평범한 JSON 객체로 직렬화된다.
 *
 * <p>모든 필드가 필수다. 이미지가 빠진 요청을 기본값으로 채워 보내면 소비자는 그것을 실제 촬영으로
 * 받아들이므로, 발행 전에 생성 단계에서 거부한다.
 *
 * @param schemaVersion 메시지 형식 버전. 본문만 남은 로그·재처리에서도 형식을 판별하기 위해 둔다
 * @param eventId item_event.event_id. 메시지 키이자 소비자가 작업을 찾는 기준이다
 * @param warehouseId 센터 ID. 소비자 측 권한 근거로 쓰지 않는다
 * @param imageRefs cam1, cam2, cam3 순서의 촬영 이미지 참조 3개. DL 서버가 세 방향 사진을 함께 받기 때문이다.
 *                  이미지 원본은 메시지에 넣지 않는다
 */
public record DlAnalysisRequest(
        int schemaVersion,
        String eventId,
        String warehouseId,
        List<String> imageRefs) {

    public static final int SCHEMA_VERSION = 1;
    public static final int CAMERA_COUNT = 3;

    public DlAnalysisRequest {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("지원하지 않는 schemaVersion: " + schemaVersion);
        }
        requireText(eventId, "eventId");
        requireText(warehouseId, "warehouseId");
        if (imageRefs == null || imageRefs.size() != CAMERA_COUNT) {
            throw new IllegalArgumentException("imageRefs는 cam1~3 순서로 " + CAMERA_COUNT + "개여야 한다");
        }
        imageRefs.forEach(ref -> requireText(ref, "imageRefs"));
        imageRefs = List.copyOf(imageRefs);
    }

    public static DlAnalysisRequest of(String eventId, String warehouseId, List<String> imageRefs) {
        return new DlAnalysisRequest(SCHEMA_VERSION, eventId, warehouseId, imageRefs);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "는 비어 있을 수 없다");
        }
    }
}
