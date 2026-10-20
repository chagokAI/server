package com.skala.chagok.dlanalysis;

import java.lang.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** 웹 단독 배포에서는 Kafka 진입점·기동 작업을 등록하지 않는다. 기존 메시지 처리 규칙은 유지한다. */
@Target(ElementType.TYPE) @Retention(RetentionPolicy.RUNTIME)
@ConditionalOnProperty(prefix = "chagok.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
public @interface KafkaIntegrationEnabled {}
