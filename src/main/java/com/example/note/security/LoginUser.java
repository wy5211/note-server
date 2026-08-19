package com.example.note.security;

/**
 * 当前登录用户 ≈ NestJS 里挂在 request.user 上的对象（record = 最接近 TS interface 的东西）
 */
public record LoginUser(Long userId, String username) {
}
