package com.example.note.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 签发与解析 ≈ @nestjs/jwt 的 JwtService（sign / verify / decode）
 * （三项目同款，温故；Phase 6 讲「事务消息回查」时会回来体会：跨系统的最终一致
 *  往往靠这类「无状态凭证 + 可重放的幂等操作」达成）
 */
@Service
@RequiredArgsConstructor
public class JwtService {

    private final JwtProperties props;

    private SecretKey key() {
        // hmacShaKeyFor 会校验密钥长度（HS256 需 >= 256bit），太短直接启动报错 —— 安全兜底
        return Keys.hmacShaKeyFor(props.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(Long userId, String username) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("type", "access")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + props.getAccessTtlMinutes() * 60_000))
                .signWith(key())
                .compact();
    }

    public String generateRefreshToken(Long userId) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("type", "refresh")
                .expiration(new Date(System.currentTimeMillis() + props.getRefreshTtlDays() * 86_400_000L))
                .signWith(key())
                .compact();
    }

    /** 解析并校验签名/有效期，失败抛 JwtException ≈ jwt.verify 的 TokenExpiredError */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();
    }
}
