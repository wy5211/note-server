package com.example.note.localmsg.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 本地消息（分布式事务方案一的载体）
 */
@Data
@TableName("local_message")
public class LocalMessage {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_SENT = 1;
    public static final int STATUS_DEAD = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String messageKey;

    private String topic;

    private String body;

    private Integer status;

    private Integer retryCount;

    private LocalDateTime nextRetryAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
