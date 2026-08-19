package com.example.note.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体 ≈ Prisma 的 User model（MyBatis-Plus 注解 = 表结构映射，三项目同款）
 */
@Data
@TableName("user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String password;

    private String nickname;

    private String avatar;

    private String bio;

    /** 积分余额 —— 只能由「余额更新 + point_ledger 流水」同事务修改（Phase 6） */
    private Integer pointTotal;

    /** @TableLogic：MP 查询自动拼 deleted=0，删除自动变 UPDATE —— 软删标记 */
    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
