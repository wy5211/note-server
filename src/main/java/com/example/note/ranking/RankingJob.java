package com.example.note.ranking;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.note.note.entity.Note;
import com.example.note.note.mapper.NoteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 热榜计算 Job：每 5 分钟重算一次「近 7 天互动加权 + 时间衰减」的分数，写入 ZSET
 *
 * ── 为什么热榜要定时算而不是实时算 ─────────────────────────────
 *   产品现象：「热榜每小时更新」而不是「谁点赞热榜立刻变」——
 *   ① 排序是全局视角，本来就不该被单次点赞牵动（抖动难看）
 *   ② 计算要扫近 7 天全部笔记，实时算 = 每次点赞都全量扫 = 库被打挂
 *   定时批量算 + ZSET 缓存结果 = 读接口毫秒级（ZREVRANGE 直接拿现成榜单）
 *
 * ── 打分公式（Hacker News 变体）────────────────────────────────
 *   score = (like*2 + collect*3 + comment*4) / (age_hours + 2)^1.5
 *   分子：互动加权和（收藏 > 评论 > 点赞：行为成本越高权重越大 —— 产品味儿）
 *   分母：年龄衰减 —— 1 小时前的笔记分母≈3.4，24 小时前≈26，一周前≈194：
 *         同样 100 互动，新笔记的分数是一周前的 ~57 倍。这就是「热」会自然冷却
 *
 * ── 工程三件套（全是复习）──────────────────────────────────────
 *   分布式锁（扫描-计算型任务，多实例必须锁，Phase 3 结论）
 *   游标分页（Phase 3 预演过，Phase 5 主菜）
 *   快照式重写（先 DEL 再写 = 幂等，本轮结果就是最终真相）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RankingJob {

    private static final String RANK_KEY = "note:rank:hot";
    private static final String LOCK_KEY = "note:job:ranking";
    private static final int BATCH_SIZE = 500;
    /** 榜单容量：只留 top 100（前端最多展示 50，留一倍余量） */
    private static final long RANK_SIZE = 100;
    /** 只统计近 7 天的笔记 */
    private static final int RECENT_DAYS = 7;

    private final NoteMapper noteMapper;
    private final StringRedisTemplate redis;
    private final RedissonClient redissonClient;

    /**
     * 每 5 分钟。cron 第三段的「星号斜杠 5」= 每 5 的倍数分钟触发。
     * （教训重演预警：这串字符不能写进 javadoc —— 星号紧跟斜杠会截断注释，
     *  Phase 2 踩坑档案第 8 条，这里换文字描述绕开）
     */
    @Scheduled(cron = "0 */5 * * * ?")
    public void scheduled() {
        computeOnce();
    }

    /** 重算一轮（public 供测试/手动补跑） */
    public void computeOnce() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, 300, java.util.concurrent.TimeUnit.SECONDS);
            if (!acquired) {
                log.info("[RankingJob] 另一实例在算，跳过本轮");
                return;
            }
            doCompute();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void doCompute() {
        LocalDateTime since = LocalDateTime.now().minusDays(RECENT_DAYS);
        Map<String, Double> scores = new HashMap<>();
        long lastId = 0L;
        int scanned = 0;

        while (true) {
            List<Note> batch = noteMapper.selectList(Wrappers.<Note>lambdaQuery()
                    .gt(Note::getId, lastId)
                    .eq(Note::getStatus, Note.STATUS_PUBLISHED)
                    .gt(Note::getCreatedAt, since)
                    .orderByAsc(Note::getId)
                    .last("LIMIT " + BATCH_SIZE));
            if (batch.isEmpty()) {
                break;
            }
            lastId = batch.get(batch.size() - 1).getId();
            scanned += batch.size();

            for (Note note : batch) {
                double s = score(note);
                // 过滤零分笔记：没互动的进榜只会稀释榜单（ZSET 里放 0 分没意义）
                if (s > 0.01) {
                    scores.put(String.valueOf(note.getId()), s);
                }
            }
        }

        // 快照式重写：先清空旧榜单再写入（本轮结果就是唯一真相 —— 幂等）
        redis.delete(RANK_KEY);
        if (!scores.isEmpty()) {
            // 批量 ZADD：Map → Set<TypedTuple>（member, score 二元组的官方容器）
            java.util.Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> tuples =
                    scores.entrySet().stream()
                            .map(e -> org.springframework.data.redis.core.ZSetOperations
                                    .TypedTuple.of(e.getKey(), e.getValue()))
                            .collect(java.util.stream.Collectors.toSet());
            redis.opsForZSet().add(RANK_KEY, tuples);
            redis.opsForZSet().removeRange(RANK_KEY, 0, -(RANK_SIZE + 1));
        }
        log.info("[RankingJob] 热榜重算完成：扫描 {} 篇，{} 篇进榜", scanned, scores.size());
    }

    /** 打分：互动加权和 / 年龄衰减 */
    private double score(Note note) {
        double engagement = note.getLikeCount() * 2.0
                + note.getCollectCount() * 3.0
                + note.getCommentCount() * 4.0;
        double ageHours = Duration.between(note.getCreatedAt(), LocalDateTime.now()).toMinutes() / 60.0;
        return engagement / Math.pow(ageHours + 2, 1.5);
    }
}
