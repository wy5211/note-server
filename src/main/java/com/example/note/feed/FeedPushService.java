package com.example.note.feed;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.note.follow.entity.Follow;
import com.example.note.follow.mapper.FollowMapper;
import com.example.note.note.entity.Note;
import com.example.note.note.mapper.NoteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Feed 写扩散（推模式）：笔记发布后，投进每个粉丝的「收件箱」
 *
 * ── 推拉结合的选型逻辑（微博/小红书真实方案的推演）──────────────
 *   纯推（写扩散）：发布时写 N 份（每粉丝一份）。素人作者 N 小，划算；
 *                  但 100w 粉的大 V 一发笔记 = 100w 次写 —— 写风暴
 *   纯拉（读扩散）：粉丝打开 Feed 时实时查所有关注人的最新笔记合并。
 *                  写零成本，但每人每次刷都是多表聚合 —— 读风暴
 *   推拉结合：素人推（写一次粉丝各得一份）、大 V 拉（读时现查）——
 *             两边的风暴都避开，代价是读路径要合并两种来源
 *
 * ── 收件箱设计 ────────────────────────────────────────────────
 *   结构：ZSET inbox:{userId}，member = noteId，score = noteId
 *   （noteId 自增，天然递增 ≈ 时间序；用时间戳会重/截断，用业务自增 ID 更稳 —— 微博同款）
 *   有界：每写一个裁剪到最近 1000 条（ZREMRANGEBYRANK）——
 *   收件箱是缓存不是数据库，无限膨胀 = 内存炸弹
 *
 * ── 性能：粉丝多时逐个 ZADD 慢 ────────────────────────────────
 *   素人上限 1000 粉，逐条也要 1000 次往返 —— pipeline 批量打包
 *   （im/mall 讲过 Redis pipeline：N 次网络往返 → 1 次）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedPushService {

    private static final String INBOX_KEY = "inbox:";
    /** 收件箱容量：只保留最近 1000 条（翻更早的直接走拉模式兜底，教学简化为固定值） */
    private static final long INBOX_SIZE = 1000;
    /** 单次推送分片 */
    private static final int FAN_BATCH = 500;

    private final NoteMapper noteMapper;
    private final FollowMapper followMapper;
    private final StringRedisTemplate redis;

    /** 大 V 判定阈值：粉丝数超过它就不推（读时拉） */
    @Value("${note.feed.push-follower-threshold:1000}")
    private int pushFollowerThreshold;

    /**
     * 笔记发布事件到达 → 分发给作者的粉丝（MQ 消费者调用）
     *
     * ⚠️ 设计要点：这里不检查「审核是否已通过」——
     *   发布事件和审核消费者几乎同时消费，推送时笔记多半还在 status=1（审核中），
     *   如果等审核通过才推，就得串起两条异步链路（复杂且更慢）。
     *   微博同款做法：先收进收件箱，读的时候再按状态过滤（myFeed 里 filter STATUS_PUBLISHED）
     *   —— 审核驳回的笔记只是躺在收件箱里永远不被读出，无害；后续被裁剪淘汰。
     *   「写时宽松、读时严格」是时间线系统的惯用姿态（删除/屏蔽内容同样处理）
     */
    public void pushToFollowers(Long noteId) {
        Note note = noteMapper.selectById(noteId);
        if (note == null) {
            return;   // 软删/不存在（@TableLogic 自动过滤 deleted=1）
        }

        List<Long> fans = followMapper.selectList(Wrappers.<Follow>lambdaQuery()
                        .eq(Follow::getFollowingId, note.getUserId()))
                .stream().map(Follow::getFollowerId).toList();

        if (fans.size() > pushFollowerThreshold) {
            log.info("[Feed] 大V笔记走拉模式（粉丝 {} > 阈值 {}），不写扩散 noteId={}",
                    fans.size(), pushFollowerThreshold, noteId);
            return;
        }

        for (int i = 0; i < fans.size(); i += FAN_BATCH) {
            List<Long> chunk = fans.subList(i, Math.min(i + FAN_BATCH, fans.size()));
            // pipeline（SessionCallback 风格）：一批粉丝的 ZADD+裁剪打包成一次网络往返
            // —— N 次往返 → 1 次，粉丝越多省得越多
            redis.executePipelined(new SessionCallback<>() {
                @SuppressWarnings({"unchecked", "rawtypes"})
                @Override
                public Object execute(RedisOperations ops) {
                    for (Long fanId : chunk) {
                        String key = INBOX_KEY + fanId;
                        ops.opsForZSet().add(key, String.valueOf(noteId), noteId);
                        // 裁剪：按排名删掉第 1000 名之前的（保留最新 1000 条），防膨胀
                        ops.opsForZSet().removeRange(key, 0, -(INBOX_SIZE + 1));
                    }
                    return null;
                }
            });
        }
        log.info("[Feed] 写扩散完成：noteId={} → {} 个收件箱", noteId, fans.size());
    }
}
