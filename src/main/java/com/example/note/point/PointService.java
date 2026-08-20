package com.example.note.point;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.note.note.entity.Note;
import com.example.note.note.mapper.NoteMapper;
import com.example.note.point.entity.PointLedger;
import com.example.note.point.mapper.PointLedgerMapper;
import com.example.note.user.entity.User;
import com.example.note.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 积分服务 —— 分布式事务的「下游系统」
 *
 * 场景设定：笔记审核通过 → 作者 +50 积分。审核（上游）与积分（下游）是两个「系统」
 * （教学里同进程，但边界按服务设计 —— 面试时把 PointService 说成独立的 point-service 即可）。
 * 两个系统之间没有分布式事务协议，只有 MQ —— 怎么保证「审核成功必有积分、且只有一份」？
 * 这就是 Phase 6 的全部。
 *
 * 消费幂等：MQ 是至少一次投递（at-least-once），重复消息必然出现 ——
 * 以流水表为幂等锚（该笔记的 PUBLISH_REWARD 流水存在 = 发过），配合消费端分布式锁防并发双发
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointService {

    public static final int PUBLISH_REWARD_POINTS = 50;

    private final NoteMapper noteMapper;
    private final UserMapper userMapper;
    private final PointLedgerMapper ledgerMapper;

    /**
     * 发放发布奖励（幂等：同一笔记只发一次）
     * @return true=本次真实发放；false=已发过（幂等命中）或条件不满足
     */
    @Transactional
    public boolean awardPublishReward(Long noteId) {
        Note note = noteMapper.selectById(noteId);
        if (note == null || note.getStatus() != Note.STATUS_PUBLISHED) {
            // 只有「已发布」的笔记才有奖励（驳回的不发；还没审完的等回查/重试再来）
            return false;
        }
        Long already = ledgerMapper.selectCount(Wrappers.<PointLedger>lambdaQuery()
                .eq(PointLedger::getNoteId, noteId)
                .eq(PointLedger::getChangeType, PointLedger.TYPE_PUBLISH_REWARD));
        if (already > 0) {
            log.debug("幂等命中：该笔记奖励已发放 noteId={}", noteId);
            return false;
        }

        // 余额 + 流水 同事务（本地事务管住自己的数据 —— 这部分不需要分布式协调）
        User user = userMapper.selectById(note.getUserId());
        if (user == null) {
            log.warn("作者不存在，奖励无法发放 noteId={} userId={}", noteId, note.getUserId());
            return false;
        }
        int newBalance = user.getPointTotal() + PUBLISH_REWARD_POINTS;
        user.setPointTotal(newBalance);
        userMapper.updateById(user);

        PointLedger ledger = new PointLedger();
        ledger.setUserId(user.getId());
        ledger.setChangeType(PointLedger.TYPE_PUBLISH_REWARD);
        ledger.setAmount(PUBLISH_REWARD_POINTS);
        ledger.setBalanceAfter(newBalance);
        ledger.setNoteId(noteId);
        ledger.setRemark("笔记审核通过奖励");
        ledgerMapper.insert(ledger);

        log.info("发布奖励已发放：userId={} +{} noteId={} 余额={}",
                user.getId(), PUBLISH_REWARD_POINTS, noteId, newBalance);
        return true;
    }
}
