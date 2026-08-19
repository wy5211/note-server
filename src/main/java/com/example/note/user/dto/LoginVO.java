package com.example.note.user.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 登录响应 ≈ NestJS authService.login() 返回的 { user, accessToken, refreshToken }
 */
@Data
@Builder
public class LoginVO {

    private Long userId;

    private String nickname;

    private String accessToken;

    private String refreshToken;
}
