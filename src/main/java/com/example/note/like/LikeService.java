package com.example.note.like;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 点赞门面 —— 三版实现共存，配置一键切换（压测对比就是这么跑的）：
 *
 *   note.like.mode=v1_db    直写 MySQL（基准线，也是降级路径的实现）
 *   note.like.mode=v2_redis Redis 扛读写（快，但库里的数落后）
 *   note.like.mode=v3_mq    Redis + MQ 攒批落库（最终形态）
 *
 * 三版功能等价、性能天差地别 —— 这就是「演进式架构」的最小教具。
 * 真实世界的切换开关旁边永远坐着一块监控仪表盘（QPS/RT/错误率），看数字说话
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LikeService {

    private final DbLikeService dbLikeService;
    private final RedisLikeService redisLikeService;
    private final MqLikeService mqLikeService;

    @Value("${note.like.mode:v3_mq}")
    private String mode;

    public void like(Long userId, Long noteId) {
        switch (mode) {
            case "v1_db" -> dbLikeService.like(userId, noteId);
            case "v2_redis" -> redisLikeService.like(userId, noteId);
            default -> mqLikeService.like(userId, noteId);
        }
    }

    public void unlike(Long userId, Long noteId) {
        switch (mode) {
            case "v1_db" -> dbLikeService.unlike(userId, noteId);
            case "v2_redis" -> redisLikeService.unlike(userId, noteId);
            default -> mqLikeService.unlike(userId, noteId);
        }
    }

    public boolean liked(Long userId, Long noteId) {
        // V1 读库；V2/V3 读 Redis —— 读路径也随版本演进
        return "v1_db".equals(mode)
                ? dbLikeService.liked(userId, noteId)
                : redisLikeService.liked(userId, noteId);
    }

    public long readCount(Long noteId) {
        return "v1_db".equals(mode)
                ? dbLikeService.countFromDb(noteId)
                : redisLikeService.readCount(noteId);
    }
}
