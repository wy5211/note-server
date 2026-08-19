package com.example.note.ranking;

import com.example.note.common.Result;
import com.example.note.note.entity.Note;
import com.example.note.note.mapper.NoteMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 热榜接口：ZREVRANGE 直接拿现成榜单 —— 毫秒级，计算成本全在 Job 侧
 */
@RestController
@RequestMapping("/api/ranking")
@RequiredArgsConstructor
public class RankingController {

    private static final String RANK_KEY = "note:rank:hot";

    private final StringRedisTemplate redis;
    private final NoteMapper noteMapper;

    @GetMapping("/hot")
    public Result<List<RankItemVO>> hot(@RequestParam(defaultValue = "50") int top) {
        Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> tuples =
                redis.opsForZSet().reverseRangeWithScores(RANK_KEY, 0, Math.min(top, 100) - 1);
        if (tuples == null || tuples.isEmpty()) {
            return Result.ok(List.of());
        }

        List<Long> ids = tuples.stream().map(t -> Long.parseLong(t.getValue())).toList();
        // 保持榜单顺序装配（selectBatchIds 返回顺序不定 → toMap 后按 ids 重排）
        var byId = noteMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Note::getId, n -> n));
        var scoreById = tuples.stream().collect(
                Collectors.toMap(t -> Long.parseLong(t.getValue()),
                        org.springframework.data.redis.core.ZSetOperations.TypedTuple::getScore));

        List<RankItemVO> items = ids.stream()
                .filter(byId::containsKey)
                .map(id -> {
                    Note n = byId.get(id);
                    return RankItemVO.builder()
                            .noteId(id)
                            .title(n.getTitle())
                            .topic(n.getTopic())
                            .likeCount(n.getLikeCount())
                            .hotScore(Math.round(scoreById.get(id) * 100.0) / 100.0)
                            .build();
                })
                .toList();
        return Result.ok(items);
    }

    /** 热榜条目（轻量：不带正文，列表页只露标题和热度） */
    @Data
    @Builder
    public static class RankItemVO {
        private Long noteId;
        private String title;
        private String topic;
        private Integer likeCount;
        private Double hotScore;
    }
}
