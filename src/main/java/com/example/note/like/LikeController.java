package com.example.note.like;

import com.example.note.common.Result;
import com.example.note.security.CurrentUser;
import com.example.note.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 点赞接口 —— 三个端点对应产品上的三个动作（点赞红心 / 再点取消 / 查看状态）
 */
@RestController
@RequestMapping("/api/notes/{noteId}/like")
@RequiredArgsConstructor
public class LikeController {

    private final LikeService likeService;

    /** 点赞（幂等：重复点无害）。登录必须 —— SecurityConfig 的 anyRequest 兜底 */
    @PostMapping
    public Result<Void> like(@CurrentUser LoginUser user, @PathVariable Long noteId) {
        likeService.like(user.userId(), noteId);
        return Result.ok(null);
    }

    /** 取消点赞（幂等） */
    @DeleteMapping
    public Result<Void> unlike(@CurrentUser LoginUser user, @PathVariable Long noteId) {
        likeService.unlike(user.userId(), noteId);
        return Result.ok(null);
    }

    /**
     * 点赞状态：liked（我赞过吗） + count（总数）。
     * GET 在 SecurityConfig 放行列表里 → 游客可达：user 为 null，liked 返回 false，count 照常
     */
    @GetMapping
    public Result<Map<String, Object>> status(@CurrentUser LoginUser user, @PathVariable Long noteId) {
        boolean liked = user != null && likeService.liked(user.userId(), noteId);
        return Result.ok(Map.of("liked", liked, "count", likeService.readCount(noteId)));
    }
}
