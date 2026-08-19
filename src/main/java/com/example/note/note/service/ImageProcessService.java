package com.example.note.note.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.note.note.entity.Note;
import com.example.note.note.entity.NoteImage;
import com.example.note.note.mapper.NoteImageMapper;
import com.example.note.note.mapper.NoteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 图片异步处理（Phase 1 从「发布主链路」挪进 MQ 消费者的部分）
 *
 * 幂等方案 #2：Redis SETNX 标记 —— 和 ReviewService 的「条件更新」对照着学：
 *   审核是状态流转 → 数据库条件更新天然幂等，零额外设施；
 *   图片处理不是状态流转（重复处理会重复压图/重复改 URL）→ 需要显式去重标记。
 *
 * ⚠️ SETNX 幂等有一个必踩的坑（下面代码里专门处理了）：
 *   如果「先 setnx 成功，处理中途抛异常」，标记已存在 → 重试永远进不来 → 这条消息死掉。
 *   正确姿势：catch 里删掉标记，把机会还给重试。看似简单，线上事故常客。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageProcessService {

    /** 处理完成标记：24h 过期（消息最多重试几小时，过了就没意义了） */
    private static final String DONE_KEY = "note:img:done:";
    private static final Duration DONE_TTL = Duration.ofHours(24);

    private final NoteMapper noteMapper;
    private final NoteImageMapper noteImageMapper;
    private final ImageProcessor imageProcessor;
    private final StringRedisTemplate redis;

    public void process(Long noteId) {
        String key = DONE_KEY + noteId;

        // SETNX：key 不存在才设置并返回 true —— 并发/重复消息只有第一个能进来
        Boolean first = redis.opsForValue().setIfAbsent(key, "1", DONE_TTL);
        if (Boolean.FALSE.equals(first)) {
            log.debug("幂等命中：图片已处理或正在处理，跳过 noteId={}", noteId);
            return;
        }

        try {
            doProcess(noteId);
        } catch (Exception e) {
            // 关键：失败要还标记，否则重试消息会被自己的幂等挡在门外
            redis.delete(key);
            // 抛出去 = NACK = 消息进重试队列（对照 Bull：job.failed() + backoff）
            throw e;
        }
    }

    private void doProcess(Long noteId) {
        // 教学钩子：标题带 "poison" 的笔记模拟处理故障 —— 用于观察重试/死信队列全链路
        Note note = noteMapper.selectById(noteId);
        if (note == null) {
            log.warn("图片处理目标不存在（可能已删除），跳过 noteId={}", noteId);
            return;
        }
        if (note.getTitle().contains("poison")) {
            throw new IllegalStateException("模拟图片处理故障（观察重试→死信链路）noteId=" + noteId);
        }

        List<NoteImage> images = noteImageMapper.selectList(Wrappers.<NoteImage>lambdaQuery()
                .eq(NoteImage::getNoteId, noteId)
                .orderByAsc(NoteImage::getSort));
        if (images.isEmpty()) {
            return;
        }

        List<String> processed = imageProcessor.process(images.stream().map(NoteImage::getUrl).toList());
        for (int i = 0; i < images.size(); i++) {
            NoteImage img = images.get(i);
            img.setUrl(processed.get(i));
            noteImageMapper.updateById(img);
        }
        log.info("图片异步处理完成 noteId={} 共 {} 张", noteId, images.size());
    }
}
