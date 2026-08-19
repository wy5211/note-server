package com.example.note.note.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 笔记实体 —— 本项目的主角表，后面一堆 Phase 都在对它动刀
 */
@Data
@TableName("note")
public class Note {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String title;

    private String content;

    /** 话题标签 —— Phase 5 刷数据的主角：老数据全是 NULL，要给 500w 行补值 */
    private String topic;

    /**
     * 状态流转（Phase 1 的主场）：
     *   0 草稿 --发布--> 1 审核中 --通过--> 2 已发布
     *                     └----驳回----> 3 已驳回
     *   1 审核中 --超时(延迟消息触发)--> 4 人工审核中（Phase 1 新增）
     *
     * mall 的 OrderStatus 状态机复习：状态只前进不跳跃，每次流转都是显式的一次 UPDATE。
     * 条件更新（WHERE status=当前态）还顺手解决了消费幂等 —— 见 ReviewService
     */
    private Integer status;

    public static final int STATUS_DRAFT = 0;
    public static final int STATUS_REVIEWING = 1;
    public static final int STATUS_PUBLISHED = 2;
    public static final int STATUS_REJECTED = 3;
    public static final int STATUS_MANUAL_REVIEW = 4;

    /**
     * 四个冗余计数：真实值活在 Redis（Phase 2/3），这里的值由定时任务批量刷回。
     * 「读走缓存、写走队列、库里的数允许短暂落后」—— 内容社区计数的标准架构，
     * 代价是「Redis 的数 ≠ MySQL 的数」，对账和刷数是绕不开的功课（Phase 3）
     */
    private Integer likeCount;
    private Integer collectCount;
    private Integer commentCount;
    private Integer readCount;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
