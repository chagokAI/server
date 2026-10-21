package com.skala.chagok.workflow;

import com.skala.chagok.domain.user.entity.AppUser;
import com.skala.chagok.global.auth.CurrentUserProvider;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** NOTI-API-02~03. 각 테스트의 트랜잭션은 롤백한다. */
@SpringBootTest(properties = "chagok.auth.enabled=false") @AutoConfigureMockMvc @Transactional
class NotificationReadFlowTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @MockitoBean CurrentUserProvider users;
    final UUID warehouse = UUID.randomUUID(), user = UUID.randomUUID(), other = UUID.randomUUID();
    final UUID older = UUID.randomUUID(), newer = UUID.randomUUID(), foreign = UUID.randomUUID();
    final Instant now = Instant.now();
    final JsonMapper json = JsonMapper.builder().build();

    String base() { return "/api/v1/warehouses/" + warehouse + "/notifications"; }

    @BeforeEach void fixtures() {
        jdbc.update("insert into warehouse(id,name) values (?, '알림 테스트')", warehouse);
        jdbc.update("insert into app_user(id,warehouse_id,employee_no,name,role) values (?,?,?,'본인','OPERATOR')",
                user, warehouse, user.toString().substring(0, 20));
        jdbc.update("insert into app_user(id,warehouse_id,employee_no,name,role) values (?,?,?,'동료','OPERATOR')",
                other, warehouse, other.toString().substring(0, 20));
        notification(older, user, "INVENTORY", now.minus(Duration.ofHours(3)));
        notification(newer, user, "PURCHASE", now.minus(Duration.ofMinutes(10)));
        notification(foreign, other, "AI", now.minus(Duration.ofHours(1)));
        when(users.getCurrentUser()).thenReturn(em.find(AppUser.class, user));
    }

    void notification(UUID id, UUID recipient, String category, Instant createdAt) {
        jdbc.update("insert into notification(id,recipient_id,category,title,message,created_at) values (?,?,?,?,?,?)",
                id, recipient, category, category + " 알림", "본문", Timestamp.from(createdAt));
    }

    /** 요청과 같은 트랜잭션이므로 변경을 DB에 내린 뒤 읽는다. */
    Instant readAt(UUID id) {
        em.flush();
        Timestamp value = jdbc.queryForObject("select read_at from notification where id = ?", Timestamp.class, id);
        return value == null ? null : value.toInstant();
    }

    JsonNode data(String content) {
        return json.readTree(content).get("data");
    }

    @Test void 단건_읽음_처리후_읽은_시각이_남는다() throws Exception {
        var body = data(mvc.perform(patch(base() + "/" + older + "/read")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(body.get("unread").asBoolean()).isFalse();
        assertThat(body.get("readAt").isNull()).isFalse();
        assertThat(readAt(older)).isNotNull();
    }

    @Test void 이미_읽은_알림을_다시_요청해도_처음_읽은_시각을_유지한다() throws Exception {
        mvc.perform(patch(base() + "/" + older + "/read")).andExpect(status().isOk());
        Instant first = readAt(older);

        mvc.perform(patch(base() + "/" + older + "/read")).andExpect(status().isOk());

        assertThat(readAt(older)).isEqualTo(first);
    }

    @Test void 남의_알림은_찾을_수_없다() throws Exception {
        mvc.perform(patch(base() + "/" + foreign + "/read")).andExpect(status().isNotFound());

        assertThat(readAt(foreign)).isNull();
    }

    @Test void through_이전_알림만_읽음_처리한다() throws Exception {
        String request = json.writeValueAsString(Map.of("through", now.minus(Duration.ofHours(1)).toString()));

        var body = data(mvc.perform(post(base() + "/read-all").contentType("application/json").content(request))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(body.get("updatedCount").asLong()).isEqualTo(1);
        assertThat(body.get("unreadCount").asLong()).isEqualTo(1);
        assertThat(readAt(older)).isNotNull();
        assertThat(readAt(newer)).isNull();
    }

    @Test void 본문_없이_호출하면_미읽음_전체를_처리하고_남의_알림은_건드리지_않는다() throws Exception {
        var body = data(mvc.perform(post(base() + "/read-all")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(body.get("updatedCount").asLong()).isEqualTo(2);
        assertThat(body.get("unreadCount").asLong()).isZero();
        assertThat(readAt(foreign)).isNull();
    }

    @Test void 처리할_미읽음이_없으면_갱신_건수가_0이다() throws Exception {
        mvc.perform(post(base() + "/read-all")).andExpect(status().isOk());

        var body = data(mvc.perform(post(base() + "/read-all")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(body.get("updatedCount").asLong()).isZero();
        assertThat(body.get("unreadCount").asLong()).isZero();
    }
}
