package com.example.note.follow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.note.common.BusinessException;
import com.example.note.follow.entity.Follow;
import com.example.note.follow.mapper.FollowMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 关注/取关 —— Feed 流的关系地基
 */
@Service
@RequiredArgsConstructor
public class FollowService {

    private final FollowMapper followMapper;

    public void follow(Long followerId, Long followingId) {
        if (followerId.equals(followingId)) {
            throw BusinessException.badRequest(40010, "不能关注自己");
        }
        // INSERT IGNORE：重复关注幂等（uk 兜底）
        followMapper.insertIgnore(followerId, followingId);
    }

    public void unfollow(Long followerId, Long followingId) {
        followMapper.delete(Wrappers.<Follow>lambdaQuery()
                .eq(Follow::getFollowerId, followerId)
                .eq(Follow::getFollowingId, followingId));
    }

    public boolean isFollowing(Long followerId, Long followingId) {
        return followMapper.selectCount(Wrappers.<Follow>lambdaQuery()
                .eq(Follow::getFollowerId, followerId)
                .eq(Follow::getFollowingId, followingId)) > 0;
    }
}
