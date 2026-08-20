package com.example.note.data;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.note.note.entity.Note;
import com.example.note.note.mapper.NoteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 【Phase 5 主戏之一】话题标签刷数：给全量 topic IS NULL 的历史笔记补标签
 *
 * ── 先看反面教材：一把梭 UPDATE ────────────────────────────────
 *   UPDATE note SET topic = '生活' WHERE topic IS NULL;   -- ❌ 生产上这么写要被追责
 *   500w 行 = 一个巨型事务：
 *     1. 行锁+间隙锁全表范围 —— 持锁期间线上读写全排队
 *     2. undo log 暴涨 —— 中途 kill 回滚时间比执行还长（几百 GB 级要回滚数小时）
 *     3. binlog 单事务巨型 —— 主从延迟暴涨，从库追不上
 *     4. 没有暂停/续跑 —— 跑一半发现问题只能干等或硬回滚
 *
 * ── 正确姿势的四要素（全部体现在下面代码里）──────────────────
 *   1. 游标分批：WHERE id > cursor ORDER BY id LIMIT n —— 每批独立小事务
 *   2. 幂等：WHERE topic IS NULL 天然幂等 —— 重跑一万遍结果一致，中断可放心重进
 *   3. 限流：批间 sleep —— 给线上流量让路（50w 行我 20 批/秒，够温和）
 *   4. 进度可见：游标写 Redis —— 中断后从断点续跑，运维随时知道跑到哪
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TopicBackfillJob {

    private static final String CURSOR_KEY = "note:backfill:topic:cursor";
    private static final int BATCH_SIZE = 2000;
    /** 批间歇息：刷数是后台任务，慢就是稳 */
    private static final long THROTTLE_MS = 50;

    private final NoteMapper noteMapper;
    private final org.springframework.data.redis.core.StringRedisTemplate redis;

    /**
     * 跑一轮全量刷数（游标从 Redis 恢复或从头开始）。
     * 幂等可重入：中断后再次调用，从上次进度继续；跑完清游标。
     */
    public void backfill() {
        String saved = redis.opsForValue().get(CURSOR_KEY);
        long cursor = saved == null ? 0 : Long.parseLong(saved);
        long total = 0;
        long batches = 0;

        while (true) {
            // 取一批「还没刷」的笔记（只取 id + title —— 刷数取列要吝啬，别 SELECT *）
            List<Note> batch = noteMapper.selectList(Wrappers.<Note>lambdaQuery()
                    .select(Note::getId, Note::getTitle)
                    .isNull(Note::getTopic)
                    .gt(Note::getId, cursor)
                    .orderByAsc(Note::getId)
                    .last("LIMIT " + BATCH_SIZE));
            if (batch.isEmpty()) {
                break;
            }
            cursor = batch.get(batch.size() - 1).getId();

            // 推断分组：同 topic 的笔记合并成一条 IN 更新（攒批思想第三次出场）
            Map<String, List<Long>> byTopic = batch.stream()
                    .collect(Collectors.groupingBy(n -> inferTopic(n.getTitle()),
                            Collectors.mapping(Note::getId, Collectors.toList())));
            byTopic.forEach((topic, ids) ->
                    noteMapper.update(Wrappers.<Note>lambdaUpdate()
                            .in(Note::getId, ids)
                            .isNull(Note::getTopic)   // 二次确认幂等条件（防御并发插入误伤）
                            .set(Note::getTopic, topic)));

            total += batch.size();
            batches++;
            // 进度写 Redis（断点续跑的锚点）—— 每批写一次，开销可忽略
            redis.opsForValue().set(CURSOR_KEY, String.valueOf(cursor));
            throttle();
            if (batches % 10 == 0) {
                log.info("[刷数] 已处理 {} 篇，游标 {}", total, cursor);
            }
        }

        redis.delete(CURSOR_KEY);   // 干净收尾：下次全量重跑（幂等）从 0 开始
        log.info("[刷数] 完成：共 {} 批 {} 篇历史笔记已补话题", batches, total);
    }

    /** 内容 → 话题推断（教学玩具：真实是 NLP 分类/人工运营打标） */
    private String inferTopic(String title) {
        if (containsAny(title, "露营", "帐篷", "海边", "骑行", "山")) {
            return "户外";
        }
        if (containsAny(title, "咖啡", "提拉米苏", "探店", "私房菜")) {
            return "美食";
        }
        if (containsAny(title, "Java", "MySQL", "NestJS", "编程", "索引")) {
            return "技术";
        }
        return "生活";
    }

    private boolean containsAny(String text, String... words) {
        return Pattern.compile(String.join("|", words)).matcher(text).find();
    }

    private void throttle() {
        try {
            Thread.sleep(THROTTLE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
