package com.skala.chagok;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * 프로젝트 .env는 개발자가 클라우드 DB·브로커·토큰을 넣는 파일이다. 테스트 JVM이 이를 읽으면 DB 주소만 막아도
 * 나머지 값(브로커, 계정, 내부 토큰)이 그대로 들어온다. 그래서 테스트 실행에서는 .env 가져오기 자체를 끈다.
 */
class TestEnvironmentIsolationTest {
    @Test void testJvmDoesNotImportTheProjectEnvFile() {
        var environment = new StandardEnvironment();
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        assertThat(environment.getPropertySources().stream().map(PropertySource::getName))
                .noneMatch(name -> name.contains("file [.env]"));
    }
}
