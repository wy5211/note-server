package com.example.note.note.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.note.common.Result;
import com.example.note.note.dto.NoteCreateDTO;
import com.example.note.note.dto.NoteVO;
import com.example.note.note.service.NoteService;
import com.example.note.security.CurrentUser;
import com.example.note.security.LoginUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 笔记接口（游客可刷，登录可发 —— 见 SecurityConfig 的授权规则）
 */
@RestController
@RequestMapping("/api/notes")
@RequiredArgsConstructor
public class NoteController {

    private final NoteService noteService;

    /** 发布 —— Phase 0 同步版：带图发一次感受下 RT（1~2 秒），这就是 Phase 1 的手术对象 */
    @PostMapping
    public Result<NoteVO> publish(@CurrentUser LoginUser user, @Valid @RequestBody NoteCreateDTO dto) {
        return Result.ok(noteService.publish(user.userId(), dto));
    }

    /** 详情 */
    @GetMapping("/{id}")
    public Result<NoteVO> detail(@PathVariable Long id) {
        return Result.ok(noteService.detail(id));
    }

    /** 发现页（时间流） */
    @GetMapping("/latest")
    public Result<Page<NoteVO>> latest(@RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "10") int size) {
        return Result.ok(noteService.latest(page, Math.min(size, 50)));
    }

    /** 用户主页 */
    @GetMapping("/user/{userId}")
    public Result<Page<NoteVO>> byUser(@PathVariable Long userId,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "10") int size) {
        return Result.ok(noteService.byUser(userId, page, Math.min(size, 50)));
    }
}
