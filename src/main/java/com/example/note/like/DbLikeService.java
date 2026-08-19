package com.example.note.like;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.note.like.entity.NoteLike;
import com.example.note.like.mapper.NoteLikeMapper;
import com.example.note.note.entity.Note;
import com.example.note.note.mapper.NoteMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 【V1】直写 MySQL —— 教学的「反面教材」，也是一切演进的基准线
 *
 * 每次点赞 = 2 个写操作挤在一个事务里：
 *   INSERT IGNORE note_like（关系） + UPDATE note SET like_count = like_count + 1（计数）
 *
 * 为什么它在热帖场景必挂（压测课上亲眼见证）：
 *   1. 行锁串行：同一篇热帖的 like_count 是同一行，UPDATE 排队执行 —— 并发越高锁等待越长
 *   2. 连接池打满：每个点赞占一个 Hikari 连接（默认 10 个），洪峰一来 200 个请求排队等 10 个连接
 *   3. 写放大：10w 点赞 = 20w 条 SQL，MySQL 磁盘 IO/CPU 直接拉满
 *
 * 但它有一样东西是 V2 给不了的：数据即时持久化，库里的数永远是对的。
 */
@Service
@RequiredArgsConstructor
public class DbLikeService {

    private final NoteLikeMapper noteLikeMapper;
    private final NoteMapper noteMapper;

    @Transactional
    public void like(Long userId, Long noteId) {
        // INSERT IGNORE 撞 uk_user_note 返回 0 —— 重复点赞天然幂等，计数不动
        int inserted = noteLikeMapper.insertIgnoreBatch(List.of(pair(userId, noteId)));
        if (inserted > 0) {
            noteMapper.update(Wrappers.<Note>lambdaUpdate()
                    .eq(Note::getId, noteId)
                    .setSql("like_count = like_count + 1"));
        }
    }

    @Transactional
    public void unlike(Long userId, Long noteId) {
        int deleted = noteLikeMapper.delete(
                Wrappers.<NoteLike>lambdaQuery()
                        .eq(NoteLike::getUserId, userId)
                        .eq(NoteLike::getNoteId, noteId));
        if (deleted > 0) {
            noteMapper.update(Wrappers.<Note>lambdaUpdate()
                    .eq(Note::getId, noteId)
                    .setSql("like_count = like_count - 1"));
        }
    }

    public boolean liked(Long userId, Long noteId) {
        return noteLikeMapper.selectCount(Wrappers.<NoteLike>lambdaQuery()
                .eq(NoteLike::getUserId, userId)
                .eq(NoteLike::getNoteId, noteId)) > 0;
    }

    /** 读计数：V1 直接读库（永远准，但每次读也压 MySQL —— 高频读是另一根稻草） */
    public int countFromDb(Long noteId) {
        Note note = noteMapper.selectById(noteId);
        return note == null ? 0 : note.getLikeCount();
    }

    private NoteLike pair(Long userId, Long noteId) {
        NoteLike p = new NoteLike();
        p.setUserId(userId);
        p.setNoteId(noteId);
        return p;
    }
}
