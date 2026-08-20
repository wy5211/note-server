-- Phase 5 事务进阶的教学舞台：操作日志表
-- 用途：演示 REQUIRES_NEW「主业务回滚，日志仍要留账」的经典场景
-- （安全审计/操作留痕的真实需求：业务失败可以回滚，但「谁在什么时候尝试了什么」不能消失）
CREATE TABLE `operate_log` (
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `user_id`    BIGINT UNSIGNED NOT NULL COMMENT '操作人',
    `action`     VARCHAR(64)     NOT NULL COMMENT '动作：PUBLISH_ATTEMPT / MIGRATION_STEP / ...',
    `detail`     VARCHAR(512)    DEFAULT NULL COMMENT '详情（教学环境随便记，生产注意脱敏）',
    `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_time` (`user_id`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '操作日志表';
