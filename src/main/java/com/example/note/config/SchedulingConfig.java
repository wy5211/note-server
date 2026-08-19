package com.example.note.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务总开关 —— Phase 2 第一次亮灯（LikeFlushService 的攒批落库就靠它）
 *
 * ≈ NestJS 的 @nestjs/schedule：ScheduleModule.forRoot() + @Cron/@Interval 装饰器
 * Java 侧：@EnableScheduling 开闸，方法上 @Scheduled(fixedDelay/cron) 声明节奏
 *
 * Phase 3 会在这里展开定时任务全家桶：cron 表达式、多实例重复执行问题（分布式锁）、
 * 「全局任务 vs 进程内任务」的区分
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
