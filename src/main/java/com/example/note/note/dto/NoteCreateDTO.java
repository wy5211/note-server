package com.example.note.note.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 发布笔记请求体
 */
@Data
public class NoteCreateDTO {

    @NotBlank(message = "标题不能为空")
    @Size(max = 100, message = "标题最长 100 字")
    private String title;

    @NotBlank(message = "正文不能为空")
    @Size(max = 10000, message = "正文最长 1 万字")
    private String content;

    /** 话题标签（可空 —— 老数据没有话题，正是 Phase 5 刷数据的由来） */
    @Size(max = 64, message = "话题最长 64 字")
    private String topic;

    /** 图片 URL 列表，0~9 张（小红书的九宫格约定） */
    @Size(max = 9, message = "最多 9 张图")
    private List<String> images;
}
