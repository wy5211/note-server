package com.example.note.follow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 关注关系：follower 关注 following（我是 follower，博主是 following）
 */
@Data
@TableName("follow")
public class Follow {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long followerId;

    private Long followingId;

    private LocalDateTime createdAt;
}
