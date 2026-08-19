package com.example.note.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求体 ≈ NestJS DTO + class-validator（@NotBlank ≈ @IsNotEmpty）
 */
@Data
public class RegisterDTO {

    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9_]{3,32}$", message = "用户名 3~32 位字母数字下划线")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码 6~64 位")
    private String password;

    @NotBlank(message = "昵称不能为空")
    @Size(max = 64, message = "昵称最长 64 字")
    private String nickname;
}
