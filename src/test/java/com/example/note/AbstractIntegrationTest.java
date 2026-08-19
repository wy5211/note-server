package com.example.note;

import com.example.note.note.entity.Note;
import com.example.note.note.mapper.NoteMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * note 集成测试基类：连 note 专属 MySQL(3309)/Redis(6381)，独立测试库 note_server_test
 * （三项目同套路：createDatabaseIfNotExist 自动建库，Testcontainers 等 Docker Desktop 兼容后可切）
 *
 * 随机端口起真实服务：测试走真 HTTP、穿完整 Security 过滤器链 —— 比 MockMvc 更接近线上
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3309/note_server_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
        "spring.datasource.username=root",
        "spring.datasource.password=root123456",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6381",
        // Phase 3 起：测试环境关闭 LikeSyncJob 自动调度（30s 节奏会干扰断言），手动直调 Job 方法
        "note.job.like-sync-auto=false"
})
public abstract class AbstractIntegrationTest {

    @LocalServerPort
    protected int port;

    @Autowired
    protected NoteMapper noteMapper;

    /**
     * 绕过发布链路直插笔记 —— 测试数据准备的标准姿势（不依赖 MQ/审核时序，完全可控）。
     * userId 填 999（无外键约束，详情页显示「未知用户」不影响断言）
     */
    protected Long insertNote(String title, String content, int status) {
        Note note = new Note();
        note.setUserId(999L);
        note.setTitle(title);
        note.setContent(content);
        note.setStatus(status);
        note.setLikeCount(0);
        note.setCollectCount(0);
        note.setCommentCount(0);
        note.setReadCount(0);
        noteMapper.insert(note);
        return note.getId();
    }

    protected void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
