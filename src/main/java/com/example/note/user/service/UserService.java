package com.example.note.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.note.common.BusinessException;
import com.example.note.security.JwtService;
import com.example.note.user.dto.LoginDTO;
import com.example.note.user.dto.LoginVO;
import com.example.note.user.dto.RegisterDTO;
import com.example.note.user.dto.UserVO;
import com.example.note.user.entity.User;
import com.example.note.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 用户服务：注册/登录/查信息（三项目同款，纯复习）
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public UserVO register(RegisterDTO dto) {
        User user = new User();
        user.setUsername(dto.getUsername());
        // BCrypt：自带盐、慢哈希 —— 与 im/mall 完全一致的选型
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(dto.getNickname());
        userMapper.insert(user);
        return toVO(user);
    }

    public LoginVO login(LoginDTO dto) {
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, dto.getUsername()));
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            // 「用户不存在」和「密码错误」给同一个提示 —— 不帮攻击者探账号
            throw BusinessException.badRequest(40101, "用户名或密码错误");
        }
        return LoginVO.builder()
                .userId(user.getId())
                .nickname(user.getNickname())
                .accessToken(jwtService.generateAccessToken(user.getId(), user.getUsername()))
                .refreshToken(jwtService.generateRefreshToken(user.getId()))
                .build();
    }

    public UserVO getById(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw BusinessException.notFound(40401, "用户不存在");
        }
        return toVO(user);
    }

    private UserVO toVO(User user) {
        return UserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .bio(user.getBio())
                .pointTotal(user.getPointTotal())
                .build();
    }
}
