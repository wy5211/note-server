-- Phase 6 分布式事务的教学设施

-- 1. 本地消息表：方案一「业务数据与消息同库同事务」的原子性载体
--    核心思想：把「要发的消息」当成业务数据写进同一个事务 —— 事务成功则消息必在表中，
--    再由中继任务扫表投递到 MQ（至少一次投递 + 消费端幂等 = 最终一致）
CREATE TABLE `local_message` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `message_key`   VARCHAR(128)    NOT NULL COMMENT '业务幂等键（如 reward:{noteId}），防重复入队',
    `topic`         VARCHAR(64)     NOT NULL COMMENT '目标 topic',
    `body`          VARCHAR(1024)   NOT NULL COMMENT '消息体 JSON',
    `status`        TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0=待投递 1=已投递 2=超过重试上限(死信)',
    `retry_count`   INT UNSIGNED    NOT NULL DEFAULT 0,
    `next_retry_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次可投递时间（指数退避）',
    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_message_key` (`message_key`),
    KEY `idx_status_retry` (`status`, `next_retry_at`) COMMENT '中继任务的扫描路径'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '本地消息表（分布式事务方案一）';

-- 2. 评论楼层：顺序消息的教学载体 —— 楼层号必须按到达顺序分配，
--    乱序消费会导致楼层跳号，这是「顺序有意义」的真实场景
ALTER TABLE `comment` ADD COLUMN `floor` INT UNSIGNED DEFAULT NULL COMMENT '楼层（顺序消息消费端分配）';
