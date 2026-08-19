package com.example.note.note.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 笔记视图（列表页/详情页通用：列表不带 images 明细时可忽略该字段）
 */
@Data
@Builder
public class NoteVO {

    private Long id;

    private Long userId;

    private String authorNickname;

    private String title;

    private String content;

    private String topic;

    private Integer status;

    private Integer likeCount;

    private Integer commentCount;

    private List<String> images;

    private LocalDateTime createdAt;
}
