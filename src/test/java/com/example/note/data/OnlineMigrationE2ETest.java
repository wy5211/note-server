package com.example.note.data;

import com.example.note.AbstractIntegrationTest;
import com.example.note.note.dto.NoteCreateDTO;
import com.example.note.note.service.NoteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5 在线迁移验收：手工版 gh-ost 六步全流程（分步驱动，便于中途验证双写）
 *
 * 测试放 data 包：要调用 OnlineMigrationService 的包私有分步方法（也演示
 * 「测试类与被测类同包」的正当理由 —— 走白盒分步，而不是只测一键黑盒）
 */
class OnlineMigrationE2ETest extends AbstractIntegrationTest {

    @Autowired
    private OnlineMigrationService migrationService;
    @Autowired
    private MigrationState migrationState;
    @Autowired
    private ShadowNoteMapper shadow;
    @Autowired
    private DataGenService dataGenService;
    @Autowired
    private NoteService noteService;

    @Test
    void full_migration_flow_with_dual_write_and_verify() {
        // 前置：补一批存量 + 记录基线
        dataGenService.generateHistoryNotes(500);
        long before = shadow.countOf("note");
        assertThat(before).isGreaterThan(0);

        try {
            // 步骤 1：影子表 + DDL（影子表 title 已是 300）
            migrationService.prepare();
            assertThat(shadow.countOf("note_shadow")).isZero();

            // 步骤 2：开双写，然后业务正常发布一篇 —— 应同步进影子表
            migrationState.enableDualWrite();
            NoteCreateDTO dto = new NoteCreateDTO();
            dto.setTitle("双写期间发布的笔记（标题会被迁移校验覆盖到）");
            dto.setContent("这行写入应同时出现在 note 和 note_shadow");
            var published = noteService.publish(999L, dto);
            assertThat(shadow.countOf("note_shadow"))
                    .as("双写生效：新发布应同步进影子表").isEqualTo(1);
            assertThat(shadow.sampleShadow(String.valueOf(published.getId()))).hasSize(1);

            // 步骤 3：存量拷贝（INSERT IGNORE：双写过的行被跳过，affected = 原表行数 - 1）
            long copied = migrationService.copyExistingData();
            assertThat(copied).as("双写行已先到，拷贝跳过它（affected = 基线）").isEqualTo(before);

            // 步骤 4：校验（数量 + 抽样内容一致）
            assertThat(migrationService.verify(copied)).isTrue();

            // 步骤 5+6：原子切换 + 删旧表
            migrationService.atomicSwapAndFinish();
        } finally {
            migrationState.disableDualWrite();
        }

        // 生效断言：note.title 已扩为 VARCHAR(300)（DDL 生效的硬证据）
        assertThat(shadow.titleColumnLength()).isEqualTo(300);
        // 数据完整：换表后行数 = 基线 + 双写新行
        assertThat(shadow.countOf("note")).isEqualTo(before + 1);
        // 换表后业务照常（MP 映射的表名没变，读写无缝）
        Long newNote = insertNote("换表后照常", "c", 2);
        assertThat(noteMapper.selectById(newNote)).isNotNull();
    }
}
