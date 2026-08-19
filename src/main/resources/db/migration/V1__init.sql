-- 「晒晒」内容社区初始化：七张核心表一次建齐
-- 风格延续 im/mall：utf8mb4 + 软删 + created_at/updated_at 双时间戳
-- 分包延续 mall 的「按业务域」（user/note/comment/like/follow/point），内容业务比电商更复杂，
-- 域内再分层（entity/mapper/service/controller/dto）——国内中大型项目的主流形态
--
-- ⚠️ 这份 schema 是「教学伏笔清单」，后续 Phase 会回来动它：
--   note.topic        空 NULL 列     → Phase 5 刷数据实战的主角（500w 老笔记补标签）
--   note.like_count   冗余计数列    → Phase 3 定时任务从 Redis 刷回 MySQL 的落点
--   note_like 唯一键  uk_user_note  → Phase 1/2 消费幂等 + 防重复点赞的数据库兜底
--   point_ledger      流水 + 余额   → Phase 6 分布式事务（账不能错）的舞台

-- 1. 用户表
CREATE TABLE `user` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `username`    VARCHAR(32)      NOT NULL COMMENT '登录账号',
    `password`    VARCHAR(100)     NOT NULL COMMENT 'BCrypt 加密后的密码',
    `nickname`    VARCHAR(64)      NOT NULL COMMENT '昵称（社区里展示的名字）',
    `avatar`      VARCHAR(255)     DEFAULT NULL COMMENT '头像 URL',
    `bio`         VARCHAR(255)     DEFAULT NULL COMMENT '个人简介',
    `point_total` INT              NOT NULL DEFAULT 0 COMMENT '积分余额（流水见 point_ledger，余额+流水双记账）',
    `deleted`     TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '软删标记 0=正常 1=已删除',
    `created_at`  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    KEY `idx_deleted` (`deleted`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户表';

-- 2. 笔记表（本项目的主角，Phase 5 在线迁移也是对它动刀）
CREATE TABLE `note` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `user_id`       BIGINT UNSIGNED NOT NULL COMMENT '作者 id',
    `title`         VARCHAR(100)    NOT NULL COMMENT '标题',
    `content`       TEXT            NOT NULL COMMENT '正文（Markdown/纯文本）',
    `topic`         VARCHAR(64)     DEFAULT NULL COMMENT '话题标签（如「周末露营」）——上线时历史数据没有，Phase 5 批量补',
    `status`        TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0=草稿 1=审核中 2=已发布 3=已驳回（Phase 1 状态流转主场）',
    `like_count`    INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '点赞数冗余列：真实值在 Redis，定时任务刷回（Phase 3）',
    `collect_count` INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '收藏数（同上）',
    `comment_count` INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '评论数（同上）',
    `read_count`    INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '阅读数（HyperLogLog 估算，Phase 4）',
    `deleted`       TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '软删标记',
    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user` (`user_id`, `deleted`),
    KEY `idx_status_created` (`status`, `created_at`) COMMENT '发现页按时间流的查询路径'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '笔记表';

-- 3. 笔记图片表（一篇笔记 1~9 张图，一对多拆表存）
CREATE TABLE `note_image` (
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `note_id`    BIGINT UNSIGNED NOT NULL COMMENT '所属笔记',
    `url`        VARCHAR(255)    NOT NULL COMMENT '图片 URL（Phase 1 的「异步图片处理」会改写这个地址）',
    `sort`       TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '图序（0~8）',
    `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_note` (`note_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '笔记图片表';

-- 4. 评论表（Phase 1 顺序消息 / Phase 4 计数 都会用到）
CREATE TABLE `comment` (
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `note_id`    BIGINT UNSIGNED NOT NULL COMMENT '评论的笔记',
    `user_id`    BIGINT UNSIGNED NOT NULL COMMENT '评论者',
    `content`    VARCHAR(500)    NOT NULL COMMENT '评论内容',
    `parent_id`  BIGINT UNSIGNED DEFAULT NULL COMMENT '回复的目标评论 id（NULL=一级评论）',
    `like_count` INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '评论点赞数（同笔记计数套路）',
    `deleted`    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '软删标记',
    `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_note` (`note_id`, `deleted`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '评论表';

-- 5. 点赞关系表（Phase 2 削峰 + Phase 3 对账的主角）
--    设计要点：谁赞了哪篇 = 天然的唯一约束。
--    「唯一键 + INSERT IGNORE / ON DUPLICATE KEY」是数据库层面防重复的最后防线，
--    上层 Redis/MQ 无论怎么抖，落库时这把锁保证不多记一次
CREATE TABLE `note_like` (
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `user_id`    BIGINT UNSIGNED NOT NULL COMMENT '点赞的人',
    `note_id`    BIGINT UNSIGNED NOT NULL COMMENT '被赞的笔记',
    `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_note` (`user_id`, `note_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '点赞关系表';

-- 6. 关注关系表（Phase 4 Feed 流「写扩散」的数据源）
CREATE TABLE `follow` (
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `follower_id`  BIGINT UNSIGNED NOT NULL COMMENT '关注者（我）',
    `following_id` BIGINT UNSIGNED NOT NULL COMMENT '被关注者（博主）',
    `created_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_follower_following` (`follower_id`, `following_id`),
    -- 反向索引：查「我的粉丝列表」（写扩散时要遍历博主的粉丝群发收件箱）
    KEY `idx_following_follower` (`following_id`, `follower_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '关注关系表';

-- 7. 积分流水表（Phase 6 分布式事务的账本）
--    账务铁律：余额可以算错重来，流水永远只追加（append-only）。
--    任何一次积分变动 = user.point_total 更新 + 一条流水，两者要在同一个事务里
CREATE TABLE `point_ledger` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `user_id`       BIGINT UNSIGNED NOT NULL COMMENT '用户',
    `change_type`   VARCHAR(32)     NOT NULL COMMENT '变动类型：PUBLISH_REWARD 发笔记奖励 / EXCHANGE 兑换曝光券 / ROLLBACK 回退',
    `amount`        INT             NOT NULL COMMENT '变动值（正=加，负=减）',
    `balance_after` INT             NOT NULL COMMENT '变动后余额（对账快照，能单表核对账目）',
    `note_id`       BIGINT UNSIGNED DEFAULT NULL COMMENT '关联笔记（发笔记奖励时）',
    `remark`        VARCHAR(255)    DEFAULT NULL COMMENT '备注',
    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_time` (`user_id`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '积分流水表';
