package com.example.note.like;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.note.like.mapper.NoteLikeMapper;
import com.example.note.note.entity.Note;
import com.example.note.note.mapper.NoteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 【Phase 3 主角二】点赞对账 Job：以 note_like 关系表（事实）为准，全量核对并修正 note.like_count
 *
 * ── 为什么需要它（LikeSyncJob 不是已经同步了吗）──────────────────
 *   同步 Job 管「增量」：只刷打脏标记的。但有些错不会打脏标记：
 *   - LikeFlushService 进程挂掉，队列里的消息蒸发（关系行没插，但 Redis 计数已 +1）
 *   - 有人直接改了库（DBA 手滑、业务 Bug）
 *   - Redis 的 count key 过期又被读路径用库里的错值回填
 *   账务系统的铁律：增量之外必须有全量对账兜底 —— 流水（note_like）永远是对的，
 *   余额（like_count）错了就从流水重算。
 *
 * ── 分布式锁：这个 Job 必须锁，而 LikeSyncJob 不用 ────────────────
 *   本任务是「扫描-处理」型：两个实例同时扫同一批行 → 重复计算、日志双份、
 *   修正互相覆盖还可能打架。Redisson 的 tryLock(0) 让后来者直接放弃本轮：
 *   分布式定时任务的经典姿势 —— 「抢锁办事，抢不到就睡」
 *
 *   tryLock 三个参数的讲究（mall 讲过看门狗，这里复习精要）：
 *     waitTime=0     ：抢不到立刻走，绝不排队（排队就失去防重复的意义）
 *     leaseTime=600s ：持锁上限。给了 leaseTime 就不用看门狗自动续期 ——
 *                      任务必须在时限内跑完，否则锁过期第二个实例会入场（要确保任务幂等！）
 *                      不给 leaseTime 则看门狗每 10s 自动续，进程活着就持有 —— 各有取舍
 *
 * ── cron 表达式（第一次正式出场）────────────────────────────────
 *   "0 0 4 * * ?" = 秒 分 时 日 月 周 → 每天凌晨 4:00:00。
 *   全量对账放凌晨：业务低峰，扫表压力小。注意第六位「周」用 ? 不用 *，
 *   因为日和周不能同时为 *（语义冲突时一个要让位）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LikeReconcileJob {

    private static final String LOCK_KEY = "note:job:like-reconcile";
    /** 游标分批大小 —— Phase 5 数据工程主菜的预演：永远不要一把梭全表 */
    private static final int BATCH_SIZE = 500;

    private final NoteMapper noteMapper;
    private final NoteLikeMapper noteLikeMapper;
    private final RedissonClient redissonClient;

    @Scheduled(cron = "0 0 4 * * ?")
    public void scheduled() {
        reconcileOnce();
    }

    /**
     * 对账一轮（public 供手动补跑/测试）。全流程：抢锁 → 游标扫 note → 对比关系表 → 修正
     */
    public void reconcileOnce() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        // tryLock(等待, 持锁, 单位)：返回 false = 另一个实例正在跑，本轮直接放弃
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, 600, TimeUnit.SECONDS);
            if (!acquired) {
                log.info("[LikeReconcileJob] 锁被其他实例持有，跳过本轮");
                return;
            }
            doReconcile();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[LikeReconcileJob] 抢锁被中断", e);
        } finally {
            // finally 里 unlock：无论正常/异常都要还锁；只还自己抢到的锁
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void doReconcile() {
        long lastId = 0L;
        long scanned = 0;
        long fixed = 0;

        while (true) {
            // 游标分页：WHERE id > lastId ORDER BY id LIMIT n
            // 对照 offset 分页 LIMIT 100000, 500：深翻页要扫过前 10w 行，越翻越慢；
            // 游标永远从 lastId 直接定位，每一批都是等成本 —— Phase 5 刷 500w 行就靠它
            List<Note> batch = noteMapper.selectList(Wrappers.<Note>lambdaQuery()
                    .gt(Note::getId, lastId)
                    .orderByAsc(Note::getId)
                    .last("LIMIT " + BATCH_SIZE));
            if (batch.isEmpty()) {
                break;
            }
            lastId = batch.get(batch.size() - 1).getId();
            scanned += batch.size();

            // 这批笔记的关系表真实计数（一条 GROUP BY 拿全部）
            List<Long> ids = batch.stream().map(Note::getId).toList();
            Map<Long, Long> actual = noteLikeMapper.countByNoteIds(ids).stream()
                    .collect(Collectors.toMap(
                            m -> ((Number) m.get("noteId")).longValue(),
                            m -> ((Number) m.get("cnt")).longValue()));

            // 对比 + 收集修正（不在循环里逐条 UPDATE —— 又是攒批的思路）
            Map<Long, Long> fixes = new HashMap<>();
            for (Note note : batch) {
                long truth = actual.getOrDefault(note.getId(), 0L);
                if (note.getLikeCount() != truth) {
                    fixes.put(note.getId(), truth);
                    log.warn("[LikeReconcileJob] 计数不一致：noteId={} 库存值={} 关系表真相={} → 修正",
                            note.getId(), note.getLikeCount(), truth);
                }
            }
            if (!fixes.isEmpty()) {
                noteLikeMapper.batchSetLikeCount(fixes);
                fixed += fixes.size();
            }
        }
        log.info("[LikeReconcileJob] 对账完成：扫描 {} 篇，修正 {} 篇", scanned, fixed);
    }
}
