package com.example.note.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理 ≈ app.useGlobalFilters(new AllExceptionsFilter())
 * （三项目同款，温故；Phase 1 会新增一类异常：MQ 消费失败的处理不走这里，在监听器内部消化）
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** DTO 校验失败 ≈ ValidationPipe 抛的 BadRequestException */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst()
                .orElse("参数校验失败");
        return ResponseEntity.badRequest().body(Result.error(400, message));
    }

    /** 业务异常 ≈ 自定义业务 Error */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException e) {
        return ResponseEntity.status(e.getStatus()).body(Result.error(e.getCode(), e.getMessage()));
    }

    /**
     * 唯一索引冲突：note 项目里意味着两件事 ——
     * 用户名被占用（uk_username）或重复点赞（uk_user_note，Phase 2 会撞上它）
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Result<Void>> handleDuplicateKey(DuplicateKeyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Result.error(409, "资源已存在（唯一约束冲突）"));
    }

    /** 兜底：未知异常记日志（日志里有堆栈），对外不泄露细节 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnknown(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Result.error(500, "服务器内部错误"));
    }
}
