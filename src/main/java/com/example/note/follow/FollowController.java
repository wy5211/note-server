package com.example.note.follow;

import com.example.note.common.Result;
import com.example.note.security.CurrentUser;
import com.example.note.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 关注接口（POST/DELETE 走 anyRequest 认证兜底）
 */
@RestController
@RequestMapping("/api/users/{userId}/follow")
@RequiredArgsConstructor
public class FollowController {

    private final FollowService followService;

    @PostMapping
    public Result<Void> follow(@CurrentUser LoginUser user, @PathVariable Long userId) {
        followService.follow(user.userId(), userId);
        return Result.ok(null);
    }

    @DeleteMapping
    public Result<Void> unfollow(@CurrentUser LoginUser user, @PathVariable Long userId) {
        followService.unfollow(user.userId(), userId);
        return Result.ok(null);
    }
}
