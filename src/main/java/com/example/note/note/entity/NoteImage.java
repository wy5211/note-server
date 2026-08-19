package com.example.note.note.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 笔记图片（一对多子表）
 */
@Data
@TableName("note_image")
public class NoteImage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long noteId;

    /** 图片 URL —— Phase 1 异步图片处理后，会从原图地址改写成压缩图地址 */
    private String url;

    private Integer sort;

    private LocalDateTime createdAt;
}
