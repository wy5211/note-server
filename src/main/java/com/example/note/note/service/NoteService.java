package com.example.note.note.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.note.common.BusinessException;
import com.example.note.note.dto.NoteCreateDTO;
import com.example.note.note.dto.NoteVO;
import com.example.note.note.entity.Note;
import com.example.note.note.entity.NoteImage;
import com.example.note.note.mapper.NoteImageMapper;
import com.example.note.note.mapper.NoteMapper;
import com.example.note.user.entity.User;
import com.example.note.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 笔记服务 —— Phase 0 同步版（故意的「病态实现」，Phase 1 的手术对象）
 *
 * ⚠️ 故意留下的病灶（现在能跑，但想象一下日活百万的社区）：
 *   1. publish() 里同步调敏感词审核 —— 审核服务抖一下，发布接口跟着抖/挂（耦合外部依赖）
 *   2. publish() 里同步 sleep 压图片 —— 用户点「发布」要盯着转圈 1~2 秒
 *   3. 全部挤在一个 HTTP 请求线程里 —— Tomcat 线程池 200 个，50 个并发发布就把池子占满，
 *      整个服务（包括刷笔记的游客）一起陪葬
 * 「同步世界到极限」的样子见过了，Phase 1 用 MQ 把「发布」和「审核+图片处理」拆开。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoteService {

    private final NoteMapper noteMapper;
    private final NoteImageMapper noteImageMapper;
    private final UserMapper userMapper;
    private final SensitiveWordChecker sensitiveWordChecker;
    private final ImageProcessor imageProcessor;

    /**
     * 发布笔记（同步病态版）：
     * 一个事务里干三件事 —— 建笔记、审内容、压图片 —— 事务/请求/外部调用全绑死。
     *
     * @Transactional 复习（mall 讲过）：方法内所有 DB 操作同生共死。
     * 这里还有个隐藏教学点：事务里含 sleep —— 事务持有连接的时间 = 全程，
     * 连接池就那么多，这叫「大事务」，Phase 5 会专门动刀。
     */
    @Transactional
    public NoteVO publish(Long userId, NoteCreateDTO dto) {
        Note note = new Note();
        note.setUserId(userId);
        note.setTitle(dto.getTitle());
        note.setContent(dto.getContent());
        note.setTopic(dto.getTopic());
        note.setStatus(Note.STATUS_REVIEWING);        // 先进「审核中」
        note.setLikeCount(0);
        note.setCollectCount(0);
        note.setCommentCount(0);
        note.setReadCount(0);
        noteMapper.insert(note);

        // 病灶 1：同步审核（外部依赖挡在主链路）
        boolean clean = sensitiveWordChecker.isClean(dto.getTitle(), dto.getContent());

        // 病灶 2：同步图片处理（sleep 等它）
        List<String> processed = imageProcessor.process(dto.getImages());
        for (int i = 0; i < processed.size(); i++) {
            NoteImage img = new NoteImage();
            img.setNoteId(note.getId());
            img.setUrl(processed.get(i));
            img.setSort(i);
            noteImageMapper.insert(img);
        }

        // 审核结论直接定生死（Phase 1 改为异步回写，用户秒回、稍后看到状态变化）
        note.setStatus(clean ? Note.STATUS_PUBLISHED : Note.STATUS_REJECTED);
        noteMapper.updateById(note);

        log.debug("笔记发布完成 id={} status={}（同步版：用户等了整条链路）", note.getId(), note.getStatus());
        return toVO(note, null, processed);
    }

    /** 详情 */
    public NoteVO detail(Long noteId) {
        Note note = noteMapper.selectById(noteId);
        if (note == null) {
            throw BusinessException.notFound(40402, "笔记不存在");
        }
        List<String> images = noteImageMapper.selectList(Wrappers.<NoteImage>lambdaQuery()
                        .eq(NoteImage::getNoteId, noteId)
                        .orderByAsc(NoteImage::getSort))
                .stream().map(NoteImage::getUrl).toList();
        return toVO(note, findAuthorNickname(note.getUserId()), images);
    }

    /**
     * 发现页：已发布笔记按时间倒序。
     * 分页复习：Page.of(page, size) ≈ Prisma 的 skip/take。
     * Phase 4 会聊内容社区的翻页难题：热榜页用 offset 深翻页又慢又重复，得换游标分页
     */
    public Page<NoteVO> latest(int page, int size) {
        Page<Note> notePage = noteMapper.selectPage(new Page<>(page, size),
                Wrappers.<Note>lambdaQuery()
                        .eq(Note::getStatus, Note.STATUS_PUBLISHED)
                        .orderByDesc(Note::getCreatedAt));
        return toVOPage(notePage, false);
    }

    /** 某用户的公开笔记页 */
    public Page<NoteVO> byUser(Long userId, int page, int size) {
        Page<Note> notePage = noteMapper.selectPage(new Page<>(page, size),
                Wrappers.<Note>lambdaQuery()
                        .eq(Note::getUserId, userId)
                        .eq(Note::getStatus, Note.STATUS_PUBLISHED)
                        .orderByDesc(Note::getCreatedAt));
        return toVOPage(notePage, false);
    }

    // ---------- 私有装配 ----------

    private Page<NoteVO> toVOPage(Page<Note> notePage, boolean withImages) {
        // 批量取作者昵称：一条 IN 查询，不做 N+1（性能课在 mall 讲过，这里是习惯养成）
        Map<Long, User> users = notePage.getRecords().isEmpty() ? Map.of()
                : userMapper.selectBatchIds(notePage.getRecords().stream().map(Note::getUserId).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));

        List<NoteVO> vos = notePage.getRecords().stream()
                .map(n -> toVO(n, users.containsKey(n.getUserId()) ? users.get(n.getUserId()).getNickname() : "未知用户",
                        withImages ? findImageUrls(n.getId()) : List.<String>of()))
                .toList();
        Page<NoteVO> voPage = new Page<>(notePage.getCurrent(), notePage.getSize(), notePage.getTotal());
        voPage.setRecords(vos);
        return voPage;
    }

    private List<String> findImageUrls(Long noteId) {
        return noteImageMapper.selectList(Wrappers.<NoteImage>lambdaQuery()
                        .eq(NoteImage::getNoteId, noteId).orderByAsc(NoteImage::getSort))
                .stream().map(NoteImage::getUrl).toList();
    }

    private String findAuthorNickname(Long userId) {
        User user = userMapper.selectById(userId);
        return user != null ? user.getNickname() : "未知用户";
    }

    private NoteVO toVO(Note note, String authorNickname, List<String> images) {
        return NoteVO.builder()
                .id(note.getId())
                .userId(note.getUserId())
                .authorNickname(authorNickname)
                .title(note.getTitle())
                .content(note.getContent())
                .topic(note.getTopic())
                .status(note.getStatus())
                .likeCount(note.getLikeCount())
                .commentCount(note.getCommentCount())
                .images(images)
                .createdAt(note.getCreatedAt())
                .build();
    }
}
