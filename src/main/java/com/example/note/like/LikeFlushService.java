package com.example.note.like;

import com.example.note.like.entity.NoteLike;
import com.example.note.like.mapper.NoteLikeMapper;
import com.example.note.mq.event.NoteLikeEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * 攒批落库（削峰的下游半场）—— 消息进来先入内存队列，定时任务每 2s 批量刷进 MySQL
 *
 * 攒批的算术：洪峰 10w 点赞/分钟 → V1 是 20w 条 SQL 砸向 MySQL；
 * 攒批后 = 按笔记分组，每组一条批量 INSERT IGNORE，最后一条 CASE WHEN 摊平所有计数 ——
 * 「合并同类项」就是削峰的数学本质。
 *
 * 幂等的回收伏笔（Phase 0 埋的 uk_user_note 在这里兑现）：
 *   flush 不信任任何上游状态，只认数据库的 affected 返回值：
 *   按笔记分组执行 INSERT IGNORE，每组返回「真实新增数」—— 消息重复投递、降级路径已写过、
 *   消费者重试，无论怎么抖，计数增量永远等于数据库真实变化。
 *
 * ⚠️ 教学简化（真实世界要补的课，注释留档）：
 *   1. 内存队列无界 —— 洪峰超过消费能力会 OOM，要加容量上限 + 背压（拒绝/降级）
 *   2. 进程挂了队列里的数据丢 —— 兜底是 Phase 3 的「对账刷新」（Redis 真相源 vs 库对账）
 *   3. @Scheduled 单机跑没问题；队列是进程内的，多实例各自刷各自的，天然不冲突 ——
 *      但注意这与「全局定时任务」的区别，Phase 3 的分布式锁话题再展开
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LikeFlushService {

    /** 每 2 秒刷一批（fixedDelay：上一轮跑完再计时，天然防重入） */
    private static final long FLUSH_INTERVAL_MS = 2_000;
    /** 批量 SQL 的分片大小：单条 SQL 别拼太长（max_allowed_packet 和解析成本） */
    private static final int BATCH_SIZE = 500;

    private final ConcurrentLinkedQueue<NoteLikeEvent> buffer = new ConcurrentLinkedQueue<>();
    private final NoteLikeMapper noteLikeMapper;

    /** 消费者调用的入口：入队即返回（毫秒级），落库是定时任务的事 */
    public void record(NoteLikeEvent event) {
        buffer.offer(event);
    }

    /** 队列水位（观察削峰效果的窗口，日志/监控用） */
    public int pendingSize() {
        return buffer.size();
    }

    @Scheduled(fixedDelay = FLUSH_INTERVAL_MS)
    public void flush() {
        // 1. drain：快照式取空队列 —— 此后新进来的消息留给下一轮，不持锁
        List<NoteLikeEvent> batch = new ArrayList<>();
        NoteLikeEvent e;
        while ((e = buffer.poll()) != null) {
            batch.add(e);
        }
        if (batch.isEmpty()) {
            return;
        }

        // 2. 按动作分组，再按笔记分组 —— 分组粒度决定了 affected 能精确到每篇笔记
        Map<Long, List<NoteLike>> likesByNote = batch.stream()
                .filter(NoteLikeEvent::like)
                .map(this::toPair)
                .collect(Collectors.groupingBy(NoteLike::getNoteId));
        Map<Long, List<NoteLike>> unlikesByNote = batch.stream()
                .filter(ev -> !ev.like())
                .map(this::toPair)
                .collect(Collectors.groupingBy(NoteLike::getNoteId));

        // 3. 执行并收集「真实增量」—— affected 是唯一可信源（幂等之锚）
        Map<Long, Integer> deltas = new HashMap<>();
        likesByNote.forEach((noteId, pairs) -> {
            int affected = 0;
            for (List<NoteLike> chunk : partition(pairs)) {
                affected += noteLikeMapper.insertIgnoreBatch(chunk);
            }
            deltas.merge(noteId, affected, Integer::sum);
        });
        unlikesByNote.forEach((noteId, pairs) -> {
            int affected = 0;
            for (List<NoteLike> chunk : partition(pairs)) {
                affected += noteLikeMapper.deletePairs(chunk);
            }
            deltas.merge(noteId, -affected, Integer::sum);
        });

        // 4. CASE WHEN 一条 SQL 摊平所有笔记的计数增量
        if (!deltas.isEmpty()) {
            noteLikeMapper.batchAddLikeCount(deltas);
        }
        log.info("点赞攒批落库：本批 {} 条（赞 {} / 取消 {}），涉及 {} 篇笔记，队列余量 {}",
                batch.size(),
                likesByNote.values().stream().mapToInt(List::size).sum(),
                unlikesByNote.values().stream().mapToInt(List::size).sum(),
                deltas.size(), pendingSize());
    }

    private NoteLike toPair(NoteLikeEvent ev) {
        NoteLike p = new NoteLike();
        p.setUserId(ev.userId());
        p.setNoteId(ev.noteId());
        return p;
    }

    private List<List<NoteLike>> partition(List<NoteLike> list) {
        List<List<NoteLike>> chunks = new ArrayList<>();
        for (int i = 0; i < list.size(); i += BATCH_SIZE) {
            chunks.add(list.subList(i, Math.min(i + BATCH_SIZE, list.size())));
        }
        return chunks;
    }
}
