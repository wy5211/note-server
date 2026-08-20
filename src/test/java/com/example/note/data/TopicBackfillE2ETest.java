package com.example.note.data;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.note.AbstractIntegrationTest;
import com.example.note.note.entity.Note;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5 刷数验收：造历史数据 → 游标分批补 topic → 全覆盖 + 幂等可重跑
 *
 * 教学量级说明：测试里 3000 行秒级跑完；「一把 UPDATE 的灾难」靠注释与 README 讲透
 * （本地想体感 50w 行：手动调 DataGenService.generateHistoryNotes(500_000) 再跑）
 */
class TopicBackfillE2ETest extends AbstractIntegrationTest {

    @Autowired
    private DataGenService dataGenService;
    @Autowired
    private TopicBackfillJob topicBackfillJob;

    @Test
    void backfill_fills_all_null_topics_and_is_reentrant() {
        dataGenService.generateHistoryNotes(3000);

        // 前置：3000 行 topic 全 NULL（造数产物）
        assertThat(countNullTopic()).isGreaterThanOrEqualTo(3000);

        topicBackfillJob.backfill();

        // 全覆盖：虚构用户 8888 的历史笔记不再有 NULL topic
        assertThat(noteMapper.selectCount(Wrappers.<Note>lambdaQuery()
                        .eq(Note::getUserId, 8888L).isNull(Note::getTopic)))
                .as("刷数后不应再有 NULL topic").isZero();

        // 推断正确性：标题含「露营」的进了「户外」
        assertThat(noteMapper.selectCount(Wrappers.<Note>lambdaQuery()
                        .eq(Note::getUserId, 8888L).eq(Note::getTopic, "户外")))
                .as("关键词推断应产出户外话题").isPositive();

        // 幂等重跑：再跑一遍 affected 为 0，数据不漂移
        long outdoor = countTopic("户外");
        topicBackfillJob.backfill();
        assertThat(countTopic("户外")).as("重跑不改变结果（幂等）").isEqualTo(outdoor);
    }

    private long countNullTopic() {
        return noteMapper.selectCount(Wrappers.<Note>lambdaQuery().isNull(Note::getTopic));
    }

    private long countTopic(String topic) {
        return noteMapper.selectCount(Wrappers.<Note>lambdaQuery().eq(Note::getTopic, topic));
    }
}
