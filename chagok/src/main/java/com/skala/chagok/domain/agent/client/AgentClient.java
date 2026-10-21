package com.skala.chagok.domain.agent.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 박스 주문 제안 에이전트 서버(box_agent/agent_server.py) 호출. docker network 안의 내부 HTTP 이고
 * X-Internal-Token(INTERNAL_API_TOKEN)으로 인증한다. 주소는 AGENT_API_URL(예: http://agent:8000).
 */
@Component
public class AgentClient {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;
    private final String token;

    public AgentClient(@Value("${chagok.agent.url:}") String baseUrl, @Value("${chagok.internal.token:}") String token) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token;
    }

    /** 에이전트 서버가 받은 SSE 이벤트 한 건 */
    public record Event(String id, String name, Map<String, Object> data) {}

    public static class AgentUnavailableException extends RuntimeException {
        public AgentUnavailableException(String message, Throwable cause) { super(message, cause); }
    }

    public boolean configured() { return !baseUrl.isBlank() && !token.isBlank(); }

    public Map<String, Object> createTask(String taskId, UUID warehouseId, String targetMonth) {
        return send("POST", "/internal/agent/tasks",
                Map.of("taskId", taskId, "warehouseId", warehouseId.toString(), "targetMonth", targetMonth));
    }

    public Map<String, Object> getTask(String taskId) {
        return send("GET", "/internal/agent/tasks/" + taskId, null);
    }

    public void approve(String taskId, String decision, String comment) {
        send("POST", "/internal/agent/tasks/" + taskId + "/approval",
                Map.of("decision", decision, "comment", comment == null ? "" : comment));
    }

    /**
     * 진행 이벤트 SSE 를 끝(DONE·FAILED)까지 읽어 넘긴다. onEvent 가 false 를 돌려주면(화면 연결 끊김) 멈춘다.
     * lastEventId 를 주면 그 다음 이벤트부터 받는다.
     */
    public void stream(String taskId, String lastEventId, Predicate<Event> onEvent) {
        var request = request("/internal/agent/tasks/" + taskId + "/events").header("Accept", "text/event-stream");
        if (lastEventId != null && !lastEventId.isBlank()) request.header("Last-Event-ID", lastEventId);
        HttpResponse<Stream<String>> response;
        try {
            response = http.send(request.GET().build(), HttpResponse.BodyHandlers.ofLines());
        } catch (IOException e) {
            throw new AgentUnavailableException("에이전트 서버에 연결할 수 없습니다.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (response.statusCode() >= 400) throw new AgentUnavailableException("에이전트 서버 응답 " + response.statusCode(), null);
        String id = null, name = null;
        StringBuilder data = new StringBuilder();
        try (Stream<String> lines = response.body()) {
            for (Iterator<String> it = lines.iterator(); it.hasNext(); ) {
                String line = it.next();
                if (line.isEmpty()) {                       // 빈 줄 = 이벤트 하나 끝
                    if (name != null && !onEvent.test(new Event(id, name, parse(data.toString())))) return;
                    id = null; name = null; data.setLength(0);
                } else if (line.startsWith("id:")) id = line.substring(3).trim();
                else if (line.startsWith("event:")) name = line.substring(6).trim();
                else if (line.startsWith("data:")) data.append(line.substring(5).trim());
            }
        }
    }

    private HttpRequest.Builder request(String path) {
        if (!configured()) throw new AgentUnavailableException("에이전트 서버 주소(AGENT_API_URL)·토큰이 설정되지 않았습니다.", null);
        return HttpRequest.newBuilder(URI.create(baseUrl + path)).header("X-Internal-Token", token);
    }

    private Map<String, Object> send(String method, String path, Object body) {
        var request = request(path).timeout(Duration.ofSeconds(20)).header("Accept", "application/json");
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        try {
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            var parsed = parse(response.body());
            if (response.statusCode() >= 400) {
                Object error = parsed.get("error");
                String message = error instanceof Map<?, ?> m ? String.valueOf(m.get("message")) : response.body();
                throw new AgentUnavailableException("에이전트 서버 " + response.statusCode() + ": " + message, null);
            }
            return parsed;
        } catch (IOException e) {
            throw new AgentUnavailableException("에이전트 서버에 연결할 수 없습니다.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentUnavailableException("에이전트 호출이 중단되었습니다.", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String text) {
        if (text == null || text.isBlank()) return Map.of();
        try {
            Object value = JSON.readValue(text, Object.class);
            return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of("value", value);
        } catch (RuntimeException e) {
            return Map.of("raw", text);
        }
    }
}
