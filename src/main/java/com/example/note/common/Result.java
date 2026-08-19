package com.example.note.common;

import lombok.Getter;

/**
 * 统一响应体 ≈ NestJS 全局 TransformInterceptor 包装出的 { code, message, data }
 * （三项目同款，温故）
 *
 * @param <T> data 的类型（泛型 ≈ TypeScript 的 <T>）
 */
@Getter
public class Result<T> {

    /** 业务状态码：0 = 成功，非 0 = 各种业务错误 */
    private final int code;

    /** 提示信息 */
    private final String message;

    /** 数据体 */
    private final T data;

    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "ok", data);
    }

    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }
}
