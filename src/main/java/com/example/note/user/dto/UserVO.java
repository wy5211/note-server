package com.example.note.user.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 用户公开信息（不含 password —— 实体永远不直接出 Controller，这是铁律）
 */
@Data
@Builder
public class UserVO {

    private Long id;

    private String username;

    private String nickname;

    private String avatar;

    private String bio;

    private Integer pointTotal;
}
