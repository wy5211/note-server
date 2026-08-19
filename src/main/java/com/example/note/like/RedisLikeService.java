package com.example.note.like;

import com.example.note.note.entity.Note;
import com.example.note.note.mapper.NoteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 【V2】Redis 扛读写 —— 「快」的问题解决了，「持久」的问题埋下了
 *
 * 数据布局（两条 key，都是 O(1) 操作）：
 *   note:like:count:{noteId}  String   计数器（INCR/DECR 单线程原子）
 *   note:like:bitmap:{noteId} BitMap   「谁赞过」（SETBIT userId 位）
 *
 * BitMap 为什么香：500w 用户一篇笔记的点赞记录 = 500w bit ≈ 625KB，
 * 换 Set 存 userId 要几十上百 MB —— 位图是空间效率的降维打击（第 4 课 HyperLogLog 的邻居）
 *
 * ⚠️ V2 的代价（Phase 3 的钩子）：MySQL 的 like_count 从此严重落后 ——
 *    Redis 是「新真相源」，但运营报表读库、Redis 宕机数据丢、重启丢内存……
 *    「Redis 的数不是库的数」就是下一课「刷数据」的由来
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisLikeService {

    private static final String COUNT_KEY = "note:like:count:";
    private static final String BITMAP_KEY = "note:like:bitmap:";
    /** 回填缓存的 TTL：防冷 key 永驻内存 */
    private static final Duration COUNT_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final NoteMapper noteMapper;

    public void like(Long userId, Long noteId) {
        String bitmap = BITMAP_KEY + noteId;
        // 先查后写有竞态窗口，但后果只是「多查一次位」—— 计数只在位从 0→1 时加，见下
        if (Boolean.TRUE.equals(redis.opsForValue().getBit(bitmap, userId))) {
            return;   // 幂等：已赞过
        }
        // setBit 三参版：置位 + 返回旧值（旧值 false = 0→1 真·首次，才是计数该加的时刻）
        Boolean wasZero = redis.opsForValue().setBit(bitmap, userId, true);
        if (Boolean.FALSE.equals(wasZero)) {                            // 0→1 才是真·首次点赞
            redis.opsForValue().increment(COUNT_KEY + noteId);
        }
    }

    public void unlike(Long userId, Long noteId) {
        String bitmap = BITMAP_KEY + noteId;
        if (!Boolean.TRUE.equals(redis.opsForValue().getBit(bitmap, userId))) {
            return;   // 幂等：本来就没赞
        }
        Boolean wasOne = redis.opsForValue().setBit(bitmap, userId, false);   // 清位，旧值 true = 1→0 真取消
        if (Boolean.TRUE.equals(wasOne)) {                              // 1→0 才真·取消
            Long after = redis.opsForValue().decrement(COUNT_KEY + noteId);
            if (after != null && after < 0) {
                // 防御：异常时序下 DECR 到负数（比如 Redis 数据部分丢失）→ 修正回 0
                // 正解是 Lua 脚本原子化「查位+清位+减数」三步 —— mall 的秒杀讲过 Lua，这里简化容忍
                redis.opsForValue().increment(COUNT_KEY + noteId);
                log.warn("计数被 DECR 到负数，已修正 noteId={}", noteId);
            }
        }
    }

    public boolean liked(Long userId, Long noteId) {
        return Boolean.TRUE.equals(redis.opsForValue().getBit(BITMAP_KEY + noteId, userId));
    }

    /**
     * 读计数：Redis miss 时回退 DB（V3 攒批落库后 DB 也有数了；缓存 TTL 内以 Redis 为准）。
     * 这就是「cache-aside 读」标准姿势 —— im-server 讲过，这里复习在计数场景的样子
     */
    public long readCount(Long noteId) {
        String v = redis.opsForValue().get(COUNT_KEY + noteId);
        if (v != null) {
            return Long.parseLong(v);
        }
        Note note = noteMapper.selectById(noteId);
        long dbCount = note == null ? 0 : note.getLikeCount();
        redis.opsForValue().set(COUNT_KEY + noteId, String.valueOf(dbCount), COUNT_TTL);
        return dbCount;
    }
}
