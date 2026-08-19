package com.example.note.like;

import com.example.note.like.mapper.NoteLikeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 【Phase 3 主角一】计数同步 Job：把 Redis 里的点赞计数增量刷回 MySQL
 *
 * 解决的债（Phase 2 欠下的）：V2/V3 模式下 Redis 是计数真相源，MySQL 严重落后 ——
 * 运营报表读库、离线分析读库、Redis 宕机恢复兜底，都要求库里的数最终是对的。
 *
 * ── 增量刷新的设计 ──────────────────────────────────────────────
 * 「哪些笔记的计数变了」由脏集合回答（点赞时 SADD），Job 只处理变化过的：
 *   SPOP 弹出脏 noteId（原子取走，天然支持多实例！弹出来的不会再给别人）
 *   → MGET 批量读 Redis 计数
 *   → CASE WHEN 一条 SQL 快照覆盖（绝对值，非增量）
 *
 * ── 竞态正确性（刷数据必答题：刷的过程中又有人点赞怎么办）──────────
 *   SPOP 之后、写库之前来了新点赞：INCR 先生效 → 本轮写的可能旧一拍，
 *   但新点赞同时 SADD 回了脏集合 → 下一轮 30s 后再刷一次 → 最终收敛。
 *   「允许短暂不一致，保证最终一致」就是这类任务的设计契约。
 *
 * ── 幂等性（三号方案：快照覆盖）────────────────────────────────
 *   Phase 1 条件更新（状态机）、Phase 2 affected 锚定（增量事件）、
 *   Phase 3 快照覆盖（刷数据）—— 三种幂等姿势凑齐，按业务形态选
 *
 * ── 为什么这个 Job 不需要分布式锁 ──────────────────────────────
 *   SPOP 是「原子取走」语义：两个实例同时跑也不会处理同一个 noteId。
 *   但注意这不能推广 —— 「扫描-处理」型任务（见 LikeReconcileJob）就必须锁。
 *   「这个任务多实例安全吗」要一个个问，不能想当然
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LikeSyncJob {

    private static final String DIRTY_KEY = "note:like:dirty";
    private static final int BATCH_SIZE = 500;

    private final StringRedisTemplate redis;
    private final NoteLikeMapper noteLikeMapper;

    /** 自动调度开关：测试环境关掉（手动直调 syncOnce 断言），生产常开 */
    @Value("${note.job.like-sync-auto:true}")
    private boolean autoSync;

    /**
     * fixedDelay 30s：上一轮跑完再计时。对照 fixedRate（固定频率，可能重叠）——
     * 刷库任务用 fixedDelay 防止上一轮还没写完下一轮又来读
     */
    @Scheduled(fixedDelay = 30_000)
    public void scheduled() {
        if (!autoSync) {
            return;
        }
        syncOnce();
    }

    /**
     * 刷一轮（public：定时任务的业务方法务必可独立调用 —— 好测试、好运维手动补跑）
     */
    public void syncOnce() {
        int totalSynced = 0;
        while (true) {
            // SPOP count：原子弹出最多 500 个脏 noteId（pop 重载返回 List）
            List<String> dirty = redis.opsForSet().pop(DIRTY_KEY, BATCH_SIZE);
            if (dirty == null || dirty.isEmpty()) {
                break;
            }

            // MGET 批量读计数（一条命令读 500 个 key，网络往返 500 次 → 1 次）
            List<String> keys = dirty.stream().map(id -> "note:like:count:" + id).toList();
            List<String> values = redis.opsForValue().multiGet(keys);

            Map<Long, Long> counts = new HashMap<>();
            for (int i = 0; i < dirty.size(); i++) {
                String v = values.get(i);
                // count key 可能已被 TTL 清掉（24h 无读写的冷笔记）→ 跳过它，留给对账任务兜底
                if (v != null) {
                    counts.put(Long.parseLong(dirty.get(i)), Long.parseLong(v));
                }
            }
            if (!counts.isEmpty()) {
                noteLikeMapper.batchSetLikeCount(counts);
                totalSynced += counts.size();
            }
            // 弹不足一批说明脏集合处理完了，防死循环
            if (dirty.size() < BATCH_SIZE) {
                break;
            }
        }
        if (totalSynced > 0) {
            log.info("[LikeSyncJob] 本轮刷回 {} 篇笔记的点赞计数（Redis → MySQL）", totalSynced);
        }
    }
}
