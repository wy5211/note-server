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
import com.example.note.note.mq.NoteEventProducer;
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
 * 笔记服务 —— Phase 1 异步版（对照 git 历史看 Phase 0 的同步病态版，差异就是整个 Phase 的教学）
 *
 * 改造前（同步版）：一个请求里 建笔记→审内容→压图片，带图 RT 600ms+，审核挂=发布挂
 * 改造后（事件驱动）：事务只做「落库原图 + status=1 审核中」，commit 后发事件，秒回；
 *   审核组、图片组各自消费，互不拖累 —— 用户的体验从「盯着转圈」变成「发完就见到（审核中）」
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoteService {

    private final NoteMapper noteMapper;
    private final NoteImageMapper noteImageMapper;
    private final UserMapper userMapper;
    private final NoteEventProducer noteEventProducer;
    private final org.springframework.data.redis.core.StringRedisTemplate redis;

    /**
     * 发布笔记（异步版）：事务体瘦身成「纯 DB 写」，耗时外部调用全部出走 MQ。
     *
     * 注意原图是直接入库的（图片处理 = 后台改写 URL），不是「等处理完再入库」——
     * 用户立即能在详情页看到原图，处理完成后 URL 变压缩版，体验无缝。
     */
    @Transactional
    public NoteVO publish(Long userId, NoteCreateDTO dto) {
        Note note = new Note();
        note.setUserId(userId);
        note.setTitle(dto.getTitle());
        note.setContent(dto.getContent());
        note.setTopic(dto.getTopic());
        note.setStatus(Note.STATUS_REVIEWING);        // 秒回的代价：返回时是「审核中」，稍后自动流转
        note.setLikeCount(0);
        note.setCollectCount(0);
        note.setCommentCount(0);
        note.setReadCount(0);
        noteMapper.insert(note);

        if (dto.getImages() != null) {
            for (int i = 0; i < dto.getImages().size(); i++) {
                NoteImage img = new NoteImage();
                img.setNoteId(note.getId());
                img.setUrl(dto.getImages().get(i));   // 原图入库，异步处理后改写成压缩版
                img.setSort(i);
                noteImageMapper.insert(img);
            }
        }

        // 事务提交后才发事件（原因见 NoteEventProducer 决策 1）
        noteEventProducer.publishAfterCommit(note.getId());

        log.debug("笔记已受理（审核中）id={} —— 接口返回，后续交给 MQ", note.getId());
        return toVO(note, null, dto.getImages() == null ? List.of() : dto.getImages());
    }

    /**
     * 详情（Phase 4 加阅读量统计：HyperLogLog）
     *
     * ── 为什么阅读量用 HyperLogLog ────────────────────────────────
     *   阅读数的产品语义是「去重 UV」，一个用户刷 100 次只算 1。
     *   精确去重要 Set（一个热帖百万人 = Set 几百 MB）；HyperLogLog 用
     *   固定 12KB 就能估算任意基数，标准误差 0.81% ——
     *   「阅读数 1,000,013 还是 1,000,821 用户根本无感」的场景，近似就是免费午餐
     *   （对照 BitMap：点赞要精确到人必须用位图；阅读只要量级，HLL 够了 —— 精度换空间的连续谱）
     *
     * @param visitor 访客标识：登录用户 u{userId}，游客 ip{x.x.x.x}（UV 的分母口径）
     */
    public NoteVO detail(Long noteId, String visitor) {
        Note note = noteMapper.selectById(noteId);
        if (note == null) {
            throw BusinessException.notFound(40402, "笔记不存在");
        }

        // PFADD：计入本次访问（幂等：同一 visitor 重复 add 不增计数 —— 天然去重）
        String readKey = "note:read:" + noteId;
        redis.opsForHyperLogLog().add(readKey, visitor);

        List<String> images = noteImageMapper.selectList(Wrappers.<NoteImage>lambdaQuery()
                        .eq(NoteImage::getNoteId, noteId)
                        .orderByAsc(NoteImage::getSort))
                .stream().map(NoteImage::getUrl).toList();

        NoteVO vo = toVO(note, findAuthorNickname(note.getUserId()), images);
        // PFCOUNT 读近似 UV 展示（库里 read_count 列的回写按 Phase 3 套路走定时任务，教学从简）
        vo.setReadCount(redis.opsForHyperLogLog().size(readKey).intValue());
        return vo;
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
