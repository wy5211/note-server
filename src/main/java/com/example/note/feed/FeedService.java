package com.example.note.feed;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.note.feed.mapper.FeedMapper;
import com.example.note.follow.mapper.FollowMapper;
import com.example.note.note.dto.NoteVO;
import com.example.note.note.entity.Note;
import com.example.note.note.entity.NoteImage;
import com.example.note.note.mapper.NoteImageMapper;
import com.example.note.note.mapper.NoteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Feed 读路径：收件箱（推）+ 大 V 实时查（拉）两路合并
 *
 * ── 游标分页（Feed 流的翻页铁律）────────────────────────────────
 *   用 noteId 当 cursor：ZREVRANGEBYSCORE inbox max (cursor] LIMIT n
 *   为什么不用 offset 页码：Feed 是活水，翻页间隙有新笔记进来，
 *   offset 会「第二页重复第一页内容」（整个列表右移了）；cursor 记录
 *   「上次读到哪里」，插入再多次也只影响未读部分 —— 内容社区翻页的标配
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedService {

    private static final String INBOX_KEY = "inbox:";

    private final StringRedisTemplate redis;
    private final NoteMapper noteMapper;
    private final NoteImageMapper noteImageMapper;
    private final FollowMapper followMapper;
    private final FeedMapper feedMapper;

    @Value("${note.feed.push-follower-threshold:1000}")
    private int pushFollowerThreshold;

    /**
     * 我的关注页。
     * @param cursor 上页最后一条 noteId（首页传 null）；返回带下一页 cursor，没有更多时为 null
     */
    public FeedPage myFeed(Long userId, Long cursor, int size) {
        // ── 路 1（推）：收件箱里拿素人作者 + 大 V 曾被手工推过的笔记 ──
        // ⚠️ 游标必须排除自己：score > cursor（严格大于），否则 cursor 那条会在下一页重复出现
        //    （经典翻页重复 bug，测试抓出来的）。
        //    API 考察：ZSetOperations 的 reverseRangeByScore 只有 double 闭区间重载，
        //    Redis 原生的 "(692" 开区间语法在这个模板层不可直达 —— 但我们的 score 是整数
        //    noteId，「开区间 (cursor,+∞)」严格等效于「闭区间 [cursor-0.5, +∞)」：
        //    数学换算替代表达式，面试里也是一问（怎么在模板 API 上模拟开区间）
        double max = cursor == null ? Double.MAX_VALUE : cursor - 0.5;
        Set<String> inboxIds = redis.opsForZSet()
                .reverseRangeByScore(INBOX_KEY + userId, 0, max, 0, size);
        List<Long> pushedIds = inboxIds == null ? List.of()
                : inboxIds.stream().map(Long::parseLong).toList();

        // ── 路 2（拉）：我关注的大 V，实时查他们的最新笔记 ──
        List<Long> bigVs = followMapper.selectBigVFollowings(userId, pushFollowerThreshold);
        List<Note> pulledNotes = bigVs.isEmpty() ? List.of()
                : feedMapper.selectBigVLatestNotes(bigVs,
                        cursor == null ? Long.MAX_VALUE : cursor, size);

        // ── 合并两路，按 noteId 倒序（新笔记在前），截取一页 ──
        Map<Long, Note> merged = new LinkedHashMap<>();
        pulledNotes.forEach(n -> merged.put(n.getId(), n));
        // 推来的 id 批量查详情（再转 Note 便于统一装配）
        if (!pushedIds.isEmpty()) {
            noteMapper.selectBatchIds(pushedIds).forEach(n -> {
                if (n.getStatus() == Note.STATUS_PUBLISHED) {
                    merged.put(n.getId(), n);
                }
            });
        }
        List<Note> page = merged.values().stream()
                .sorted((a, b) -> Long.compare(b.getId(), a.getId()))
                .limit(size)
                .toList();

        Long nextCursor = page.size() < size ? null : page.get(page.size() - 1).getId();
        return new FeedPage(toVOs(page), nextCursor);
    }

    private List<NoteVO> toVOs(List<Note> notes) {
        if (notes.isEmpty()) {
            return List.of();
        }
        Map<Long, List<String>> images = noteImageMapper.selectList(
                        Wrappers.<NoteImage>lambdaQuery()
                                .in(NoteImage::getNoteId, notes.stream().map(Note::getId).toList())
                                .orderByAsc(NoteImage::getSort))
                .stream().collect(Collectors.groupingBy(NoteImage::getNoteId,
                        Collectors.mapping(NoteImage::getUrl, Collectors.toList())));
        return notes.stream().map(n -> NoteVO.builder()
                .id(n.getId())
                .userId(n.getUserId())
                .title(n.getTitle())
                .content(n.getContent())
                .topic(n.getTopic())
                .status(n.getStatus())
                .likeCount(n.getLikeCount())
                .commentCount(n.getCommentCount())
                .readCount(n.getReadCount())
                .images(images.getOrDefault(n.getId(), List.of()))
                .createdAt(n.getCreatedAt())
                .build()).toList();
    }

    /** Feed 分页响应：列表 + 下一页游标（null = 没有更多） */
    public record FeedPage(List<NoteVO> notes, Long nextCursor) {
    }
}
