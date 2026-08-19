package com.example.note;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 「晒晒」内容社区 —— 第三个学习项目
 *
 * 三项目定位对照：
 *   im-server   = 实时通讯（长连接、在线状态）
 *   mall-server = 交易一致性（并发、状态机、防超卖）
 *   note-server = 内容规模（MQ 异步、削峰、刷数据、Feed 流）   ← 你在这里
 *
 * 与 NestJS 的 main.ts + AppModule 完全同构：
 *   @SpringBootApplication ≈ @Module 聚合 + NestFactory.create()
 */
@SpringBootApplication
public class NoteApplication {

    public static void main(String[] args) {
        SpringApplication.run(NoteApplication.class, args);
    }
}
