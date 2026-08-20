package com.example.note.data;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 影子表操作 —— 在线迁移的每一步都是裸 SQL（DDL/DML 混合，MP 帮不上忙）
 *
 * 本次迁移任务（教学设定）：note 表 8000w 行，要把 title VARCHAR(100) 改成 VARCHAR(300)。
 * 为什么必须走影子表而不是直接 ALTER：
 *   MySQL 8.0 的 DDL 三档速度 —— instant（秒级，只改元数据）/ inplace（自建临时文件，
 *   允许并发 DML）/ copy（锁表复制）。加列是 instant，改列类型是 copy ——
 *   8000w 行 copy = 分钟级到小时级锁写，线上事故。所以：影子表慢慢来，最后一瞬间换过去。
 *   （gh-ost / pt-osc 就是把这个流程自动化 + 用 binlog 代替代码双写）
 */
@Mapper
public interface ShadowNoteMapper {

    /** 步骤 1a：建影子表（结构完全复制 note，含全部索引） */
    @Update("CREATE TABLE note_shadow LIKE note")
    void createShadowTable();

    /** 步骤 1b：在影子表上做真正的变更（影子表没流量，随便改） */
    @Update("ALTER TABLE note_shadow MODIFY title VARCHAR(300) NOT NULL COMMENT '标题'")
    void applySchemaChange();

    /** 步骤 2a：双写 —— 原表新写入的行同步进影子表（INSERT SELECT 一行） */
    @Insert("INSERT INTO note_shadow SELECT * FROM note WHERE id = #{noteId}")
    int dualWrite(Long noteId);

    /**
     * 步骤 3：存量分批拷贝（按 id 区间，小事务）。
     * ⚠️ 必须 INSERT IGNORE：拷贝区间会撞上「双写已进来的行」（主键冲突）——
     *    双写与存量拷贝的重叠是 gh-ost 的经典细节：先到者赢，后来者跳过即可
     *    （真 gh-ost 同样靠幂等写处理这种竞态）
     */
    @Insert("INSERT IGNORE INTO note_shadow SELECT * FROM note WHERE id > #{fromId} AND id <= #{toId}")
    int copyRange(Long fromId, Long toId);

    /** 步骤 3 辅助：note 表最大 id（游标终点） */
    @Select("SELECT MAX(id) FROM note")
    Long maxNoteId();

    /** 步骤 4：数量校验 */
    @Select("SELECT COUNT(*) FROM ${table}")
    long countOf(String table);

    /**
     * 步骤 4：抽样内容校验 —— 抽 N 行比对两表的标题/正文是否一致
     * （返回两表按 id 对齐的行，Java 侧逐行比对 —— SQL 做集合对齐不如取回内存直观）
     */
    @Select("SELECT id, title, content FROM note WHERE id IN (${ids})")
    List<Map<String, Object>> sampleNote(String ids);

    @Select("SELECT id, title, content FROM note_shadow WHERE id IN (${ids})")
    List<Map<String, Object>> sampleShadow(String ids);

    /** 步骤 5：原子切换（MySQL 多表 RENAME 是原子的 —— 切换瞬间无中间态） */
    @Update("RENAME TABLE note TO note_old, note_shadow TO note")
    void atomicSwap();

    /** 步骤 6：观察期过后删旧表（留 24~48h 观察窗口是惯例，教学里直接删） */
    @Update("DROP TABLE IF EXISTS note_old")
    void dropOldTable();

    /** 验证 note.title 的当前列宽（迁移生效的证据） */
    @Select("SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS " +
            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'note' AND COLUMN_NAME = 'title'")
    Long titleColumnLength();

    /** 清理：教学可重复跑（前置防残留） */
    @Update("DROP TABLE IF EXISTS note_shadow")
    void dropShadowIfAny();
}
