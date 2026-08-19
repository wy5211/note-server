package com.example.note.feed.mapper;

import com.example.note.note.entity.Note;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Feed 拉模式查询（跨 feed/note 两域，单独安家）
 */
@Mapper
public interface FeedMapper {

    /**
     * 大 V 们的最新已发布笔记（id < cursor 倒序取 size 条）。
     * 真实世界给 (user_id, id) 建联合索引，否则大 V 多了会慢 —— 建表时 idx_user 已覆盖前缀
     */
    @Select("<script>" +
            "SELECT * FROM note WHERE status = 2 AND id &lt; #{cursor} AND user_id IN " +
            "<foreach collection='authorIds' item='aid' open='(' close=')' separator=','>#{aid}</foreach>" +
            " ORDER BY id DESC LIMIT #{size}" +
            "</script>")
    List<Note> selectBigVLatestNotes(@Param("authorIds") List<Long> authorIds,
                                     @Param("cursor") Long cursor,
                                     @Param("size") int size);
}
