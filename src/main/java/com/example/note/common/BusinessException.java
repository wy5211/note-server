package com.example.note.common;

import lombok.Getter;

/**
 * 业务异常 ≈ NestJS 里 throw new BadRequestException('xxx')
 * service 层发现业务规则不满足时抛出，GlobalExceptionHandler 统一翻译成 HTTP 响应
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 业务错误码（40101 未登录 / 40401 笔记不存在……前端据此做精细提示） */
    private final int code;

    /** HTTP 状态码 */
    private final int status;

    public BusinessException(int code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    /** 最常用：400 + 业务码（Nest 的 BadRequestException 顺手版） */
    public static BusinessException badRequest(int code, String message) {
        return new BusinessException(code, 400, message);
    }

    public static BusinessException notFound(int code, String message) {
        return new BusinessException(code, 404, message);
    }
}
