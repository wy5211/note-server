package com.example.note.feed;

import com.example.note.common.Result;
import com.example.note.feed.FeedService.FeedPage;
import com.example.note.security.CurrentUser;
import com.example.note.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 关注页接口 —— 游标分页的对外形态
 *
 * 用法：首页 GET /api/feed?size=20 → 返回 { notes, nextCursor }
 *       翻页 GET /api/feed?cursor={nextCursor}&size=20
 * 对照页码分页 ?page=2：Feed 活水下页码会重复/漏内容，cursor 永远稳
 */
@RestController
@RequiredArgsConstructor
public class FeedController {

    private final FeedService feedService;

    @GetMapping("/api/feed")
    public Result<FeedPage> myFeed(@CurrentUser LoginUser user,
                                   @RequestParam(required = false) Long cursor,
                                   @RequestParam(defaultValue = "20") int size) {
        return Result.ok(feedService.myFeed(user.userId(), cursor, Math.min(size, 50)));
    }
}
