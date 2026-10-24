package com.skala.chagok.domain.job.config;

import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
public class JobSchedulingConfig {
    @Bean public Clock jobClock() { return Clock.systemUTC(); }

    @Bean public ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("chagok-job-");
        return scheduler;
    }
}
