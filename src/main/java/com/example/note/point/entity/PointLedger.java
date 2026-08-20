package com.example.note.point.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 积分流水（V1 埋的账本，Phase 6 正式启用）
 *
 * 账务铁律：余额(user.point_total) + 流水(point_ledger) 同事务变更，
 * 流水 append-only 永不修改 —— 对账时流水就是唯一真相
 */
@Data
@TableName("point_ledger")
public class PointLedger {

    public static final String TYPE_PUBLISH_REWARD = "PUBLISH_REWARD";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String changeType;

    /** 正=加分 负=扣分 */
    private Integer amount;

    /** 变动后余额快照（单表可对账） */
    private Integer balanceAfter;

    private Long noteId;

    private String remark;

    private LocalDateTime createdAt;
}
