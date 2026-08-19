package com.example.note.like.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.note.like.entity.NoteLike;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 点赞 Mapper —— MP BaseMapper 与手写 XML 共存的示范
 *
 * 为什么这里必须手写 SQL：V3 攒批落库要的是「批量 + 原生语义」的精确控制，
 * MP 的便捷方法（单条 insert / updateById）干不了这三件事：
 *   1. insertIgnoreBatch：批量插入 + IGNORE（撞 uk_user_note 静默跳过）→ affected 就是幂等信号
 *   2. deletePairs：按 (user_id, note_id) 行构造器批量删 → affected 同样是幂等信号
 *   3. batchAddLikeCount：CASE WHEN 一条 SQL 给多篇笔记加不同的 delta —— 刷数据的经典姿势，
 *      Phase 3 的定时刷新还会大量用到这个写法
 *
 * 对应 SQL 在 resources/mapper/NoteLikeMapper.xml（MP 默认扫 classpath 下 mapper 目录的全部 xml）
 *
 * ⚠️ 顺手踩的坑：注释里写 ant 路径通配符（如 classpath 后跟 mapper 双星斜杠 xml）时，
 *    其中的斜杠+星号序列会把 javadoc 提前闭合，后面的中文直接变非法字符 ——
 *    编译器报「非法字符 ）」时先想想是不是注释被截断了
 */
@Mapper
public interface NoteLikeMapper extends BaseMapper<NoteLike> {

    /**
     * 批量 INSERT IGNORE
     * @return 实际插入行数（重复的对儿被唯一键挡掉，不计入 —— 这个返回值就是幂等依据）
     */
    int insertIgnoreBatch(@Param("pairs") List<NoteLike> pairs);

    /**
     * 按 (userId, noteId) 对批量删除（取消赞）
     * @return 实际删除行数
     */
    int deletePairs(@Param("pairs") List<NoteLike> pairs);

    /**
     * CASE WHEN 批量累加计数
     * @param deltas key=noteId, value=增量（可正可负）
     * @return 影响行数
     */
    int batchAddLikeCount(@Param("deltas") Map<Long, Integer> deltas);
}
