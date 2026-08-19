package com.example.note.like.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 点赞关系实体 —— 「谁赞了哪篇」的持久化真相
 *
 * uk_user_note 唯一键在 Phase 2 的地位飙升：
 *   V1 它防重复计数；V3 它是 MQ 重复消费的最后一道幂等防线（INSERT IGNORE 的 affected
 *   决定计数加不加 —— 数据库约束兜底，上层怎么抖都不出错）
 */
@Data
@TableName("note_like")
public class NoteLike {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long noteId;

    private LocalDateTime createdAt;
}
