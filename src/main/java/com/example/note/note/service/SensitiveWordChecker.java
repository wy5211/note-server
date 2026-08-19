package com.example.note.note.service;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 敏感词审核（教学玩具版：真实世界是「DFA 词表 / 云端内容安全 API（阿里绿网/网易云盾）」，
 * 共同点是：都是外部依赖，都可能慢、都可能挂 —— 所以 Phase 1 要把它挪出主链路）
 */
@Component
public class SensitiveWordChecker {

    private static final List<String> BLOCK_WORDS = List.of("赌博", "代开发票", "违禁药品");

    /**
     * @return true = 内容干净，放行
     */
    public boolean isClean(String title, String content) {
        String text = title + " " + content;
        return BLOCK_WORDS.stream().noneMatch(text::contains);
    }
}
