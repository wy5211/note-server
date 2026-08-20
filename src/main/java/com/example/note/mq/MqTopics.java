package com.example.note.mq;

/**
 * Topic / 消费组 常量集中管理（≈ Bull 注册队列名常量）
 *
 * Topic 设计原则：
 *   - 一个「业务事件」一个 Topic：note-publish = 笔记发布这件事发生了
 *   - 多个下游各自消费组独立订阅同一 Topic，互不影响 —— 解耦的本质：
 *     以后加「ES 同步」「积分奖励」= 新增消费组，发布方一行不改
 *
 * ⚠️ 生产环境 Topic 是要申请管控的（autoCreateTopicEnable=false），
 *    乱建 Topic 是大厂 MQ 治理红线；命名规范：{业务域}-{事件}
 */
public final class MqTopics {

    /** 笔记发布事件（审核组、图片组都订阅它） */
    public static final String NOTE_PUBLISH = "note-publish";

    /** 审核超时检查（延迟消息专用 topic） */
    public static final String NOTE_REVIEW_TIMEOUT = "note-review-timeout";

    /** 点赞事件（Phase 2 削峰主场：洪峰进队列，消费者攒批匀速落库） */
    public static final String NOTE_LIKE = "note-like";

    /** 发布奖励事件（Phase 6 分布式事务：本地消息表 / 事务消息 两方案的载体） */
    public static final String POINT_REWARD = "point-reward";

    /** 评论创建事件（Phase 6 顺序消息：按 noteId 进同队列，楼层号才有意义） */
    public static final String COMMENT_CREATE = "comment-create";

    /** 图片消费组的死信队列：RocketMQ 约定名 %DLQ%{consumerGroup}，16 次重试都失败的消息最终流到这里 */
    public static final String IMAGE_DLQ = "%DLQ%note-image-group";

    // ---------- 消费组 ----------
    public static final String REVIEW_GROUP = "note-review-group";
    public static final String IMAGE_GROUP = "note-image-group";
    public static final String REVIEW_TIMEOUT_GROUP = "note-review-timeout-group";
    public static final String LIKE_GROUP = "note-like-group";
    public static final String POINT_REWARD_GROUP = "point-reward-group";
    public static final String COMMENT_GROUP = "comment-group";

    /**
     * Phase 4 新消费组：Feed 写扩散。
     * 兑现 Phase 1 的承诺 —— 「以后加下游 = 新增消费组，发布方一行不改」：
     * FeedPushConsumer 订阅的还是 note-publish，NoteService/Producer 零改动
     */
    public static final String FEED_GROUP = "note-feed-group";

    private MqTopics() {
    }
}
