package com.example.note.user.controller;

import com.example.note.common.Result;
import com.example.note.security.CurrentUser;
import com.example.note.security.LoginUser;
import com.example.note.user.dto.LoginDTO;
import com.example.note.user.dto.LoginVO;
import com.example.note.user.dto.RegisterDTO;
import com.example.note.user.dto.UserVO;
import com.example.note.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口：注册/登录/我的信息（三项目同套路）
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @PostMapping("/register")
    public Result<UserVO> register(@Valid @RequestBody RegisterDTO dto) {
        return Result.ok(userService.register(dto));
    }

    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.ok(userService.login(dto));
    }

    @GetMapping("/me")
    public Result<UserVO> me(@CurrentUser LoginUser user) {
        return Result.ok(userService.getById(user.userId()));
    }
}
