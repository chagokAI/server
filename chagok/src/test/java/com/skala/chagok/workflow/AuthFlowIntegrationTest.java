package com.skala.chagok.workflow;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "chagok.auth.enabled=true") @AutoConfigureMockMvc @Transactional
class AuthFlowIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    final UUID warehouse = UUID.randomUUID(), user = UUID.randomUUID();
    final String employee = "auth-" + UUID.randomUUID().toString().substring(0, 12);
    final JsonMapper json = JsonMapper.builder().build();
    record Browser(MockHttpSession session, String header, String token) {}

    @BeforeEach void fixtures() {
        jdbc.update("insert into warehouse(id,name) values (?,'인증 테스트')", warehouse);
        jdbc.update("insert into app_user(id,warehouse_id,employee_no,password_hash,name,role) values (?,?,?,?,'운영자','OPERATOR')",
                user, warehouse, employee, passwords.encode("test-password"));
    }
    Browser csrf(MockHttpSession session) throws Exception {
        var request = get("/api/v1/auth/csrf"); if (session != null) request.session(session);
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        var data = json.readTree(result.getResponse().getContentAsString()).path("data");
        return new Browser((MockHttpSession) result.getRequest().getSession(false), data.path("headerName").asText(), data.path("token").asText());
    }
    Browser login(boolean remember) throws Exception {
        var browser = csrf(null);
        mvc.perform(post("/api/v1/auth/login").session(browser.session()).header(browser.header(), browser.token())
                .contentType("application/json").content(json.writeValueAsString(Map.of("employeeId", employee,
                        "password", "test-password", "rememberMe", remember))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.warehouse.id").value(warehouse.toString()))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
        return csrf(browser.session());
    }

    @Test void loginPersistsSessionRotatesIdAndMeUsesTheAuthenticatedUser() throws Exception {
        var browser = csrf(null); var oldId = browser.session().getId();
        mvc.perform(post("/api/v1/auth/login").session(browser.session()).header(browser.header(), browser.token())
                .contentType("application/json").content(json.writeValueAsString(Map.of("employeeId", employee, "password", "test-password"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("OPERATOR"));
        assertThat(browser.session().getId()).isNotEqualTo(oldId);
        mvc.perform(get("/api/v1/auth/me").session(browser.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(user.toString())).andExpect(jsonPath("$.data.employeeId").value(employee));
    }

    @Test void incorrectPasswordAndMissingSessionReturnCanonicalUnauthorized() throws Exception {
        var browser = csrf(null);
        mvc.perform(post("/api/v1/auth/login").session(browser.session()).header(browser.header(), browser.token())
                .contentType("application/json").content(json.writeValueAsString(Map.of("employeeId", employee, "password", "wrong"))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/auth/me").header("X-Request-Id", "auth-request-1")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.error.requestId").value("auth-request-1"));
    }

    @Test void mutationRequiresCsrfAndViewerCannotEditEvenWithValidToken() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        jdbc.update("update app_user set role='VIEWER' where id=?", user);
        var browser = login(false);
        var path = "/api/v1/warehouses/" + warehouse + "/product-matches";
        mvc.perform(get(path).session(browser.session())).andExpect(status().isOk());
        mvc.perform(post("/api/v1/warehouses/" + warehouse + "/ai-jobs").session(browser.session())
                .header(browser.header(), browser.token()).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test void sessionCannotReadOrWriteAnotherWarehouse() throws Exception {
        var browser = login(false);
        String base = "/api/v1/warehouses/" + UUID.randomUUID();
        mvc.perform(get(base + "/box-specs").session(browser.session())).andExpect(status().isForbidden());
        mvc.perform(post(base + "/ai-jobs").session(browser.session()).header(browser.header(), browser.token())
                .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test void logoutInvalidatesSessionAndRememberMeSetsCookieLifetime() throws Exception {
        var browser = login(true);
        assertThat(browser.session().getMaxInactiveInterval()).isEqualTo(7 * 86400);
        mvc.perform(post("/api/v1/auth/logout").session(browser.session()).header(browser.header(), browser.token()))
                .andExpect(status().isNoContent()).andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        assertThat(browser.session().isInvalid()).isTrue();
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test void onlyConfiguredBrowserOriginsPassCorsPreflight() throws Exception {
        mvc.perform(options("/api/v1/auth/login").header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "content-type,x-csrf-token"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        mvc.perform(options("/api/v1/auth/login").header("Origin", "https://unconfigured.example")
                .header("Access-Control-Request-Method", "POST")).andExpect(status().isForbidden());
    }
}
