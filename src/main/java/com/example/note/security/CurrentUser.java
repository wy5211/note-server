package com.example.note.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 自定义参数注解 ≈ NestJS 的 createParamDecorator：
 * 用法 @GetMapping("/me") me(@CurrentUser LoginUser user) {}
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
