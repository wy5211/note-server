package com.example.note.mq.event;

/**
 * 点赞事件（Phase 2 削峰主角）
 *
 * 沿用「只发 ID」铁律；点赞必须带 userId（谁赞的），否则消费端没法落关系表
 */
public record NoteLikeEvent(Long userId, Long noteId, boolean like) {
}
