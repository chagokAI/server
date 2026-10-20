package com.skala.chagok.dlanalysis;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * DL 측정 서버의 POST /measure를 호출한다. cam1, cam2, cam3 순서의 원본 이미지 3장을 multipart로 보낸다.
 *
 * <p>실패는 재시도 여부로 나눠 알린다. DL이 4xx로 거부한 요청은 다시 보내도 같은 결과라 DL_REJECTED,
 * 연결 실패·시간 초과·5xx는 일시 장애일 수 있어 DL_UNAVAILABLE(재시도 대상)이다.
 *
 * <p>이 프로젝트에는 Spring Boot의 HTTP 클라이언트 자동 설정 모듈이 없어, Spring의 RestClient를
 * JDK HttpClient로 직접 구성한다. uvicorn과의 연결에서 HTTP/2 업그레이드 협상을 피하려고 HTTP/1.1로 고정한다.
 */
@Component
public class DlMeasureClient {

    private final RestClient restClient;

    DlMeasureClient(DlMeasureClientProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }

    public DlMeasureResult measure(List<String> imageRefs) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        for (String imageRef : imageRefs) {
            Path image = Path.of(imageRef);
            if (!Files.isReadable(image)) {
                throw new DlAnalysisException(DlAnalysisFailureReason.IMAGE_UNREADABLE, "이미지를 읽을 수 없다: " + imageRef);
            }
            parts.add("images", new FileSystemResource(image));
        }
        try {
            DlMeasureResult result = restClient.post()
                    .uri("/measure")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        throw new DlAnalysisException(DlAnalysisFailureReason.DL_REJECTED,
                                "DL이 요청을 거부했다: HTTP " + response.getStatusCode().value());
                    })
                    .body(DlMeasureResult.class);
            if (result == null) {
                throw new DlAnalysisException(DlAnalysisFailureReason.DL_UNAVAILABLE, "DL 응답 본문이 비어 있다");
            }
            return result;
        } catch (RestClientException e) {
            throw new DlAnalysisException(DlAnalysisFailureReason.DL_UNAVAILABLE, "DL 호출 실패", e);
        }
    }
}
