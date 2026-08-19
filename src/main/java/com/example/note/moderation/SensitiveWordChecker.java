package com.example.note.moderation;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 敏感词审核（从 note/service 挪到 moderation 域 —— 异步化后它是「审核域」的内部实现）
 *
 * 教学玩具版：真实世界是 DFA 词表 / 云端内容安全 API（阿里绿网/网易云盾），
 * 共同点：都是外部依赖，都可能慢、都可能挂 —— 这正是 Phase 1 把它挪出主链路的原因。
 */
@Component
public class SensitiveWordChecker {

    private static final List<String> BLOCK_WORDS = List.of("赌博", "代开发票", "违禁药品");

    /** @return true = 内容干净，放行 */
    public boolean isClean(String title, String content) {
        String text = title + " " + content;
        return BLOCK_WORDS.stream().noneMatch(text::contains);
    }
}
