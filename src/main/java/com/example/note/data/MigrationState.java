package com.example.note.data;

import org.springframework.stereotype.Component;

/**
 * 迁移运行时状态 —— 双写开关。
 * 不用 @Value：它是启动期注入的只读配置；双写要在迁移开始时开、结束后关，需要运行时可变。
 * volatile：保证多线程（写请求线程 vs 迁移线程）立即可见
 */
@Component
public class MigrationState {

    private volatile boolean dualWrite = false;

    public boolean isDualWriteEnabled() {
        return dualWrite;
    }

    public void enableDualWrite() {
        this.dualWrite = true;
    }

    public void disableDualWrite() {
        this.dualWrite = false;
    }
}
