package com.example.note.data;

import com.example.note.note.entity.Note;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 历史数据生成器 —— 「造数」本身就是数据工程的必修课
 *
 * 场景：上线三年后产品说「给全部历史笔记补话题标签」—— 你得先有一堆没有
 * topic 的历史数据（V1 建表时 topic 就是 NULL，伏笔在此兑现）。
 *
 * 性能要点：多值 INSERT（一条语句几百个 VALUES）比逐条 insert 快两个数量级 ——
 * 网络往返和事务开销都摊薄了。50w 行 ≈ 十几秒；500w 行同理（分批 + 进度日志）。
 * 生产造数还有更快姿势：LOAD DATA INFILE / 都是从「减少往返」这一个原则出发
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataGenService {

    private final HistoryNoteMapper historyNoteMapper;

    /**
     * 造 count 篇「历史笔记」：topic = NULL、status=2、作者是虚构用户
     * 标题从样例池轮转，让后续刷数时有话题可推断
     */
    public void generateHistoryNotes(int count) {
        String[] titles = {
                "周末去山里露营的装备清单", "这家咖啡店的Dirty绝了", "Java 并发编程避坑指南",
                "城市骑行 100 公里路线分享", "下雨天在家做提拉米苏", "MySQL 索引失效的十种姿势",
                "海边帐篷日出实拍", "探店：巷子里的私房菜", "聊聊 NestJS 依赖注入"
        };
        int batch = 2000;
        long generated = 0;
        for (int from = 0; from < count; from += batch) {
            int size = Math.min(batch, count - from);
            List<String> rows = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                rows.add(titles[(int) ((generated + i) % titles.length)]);
            }
            historyNoteMapper.insertHistoryBatch(rows);
            generated += size;
            if (generated % 100_000 < batch) {
                log.info("[造数] 已生成 {}/{}", generated, count);
            }
        }
        log.info("[造数] 完成，共 {} 篇历史笔记（topic 全为 NULL）", generated);
    }

    /** 造数专用 SQL（教学数据不走业务校验，直接裸插） */
    @Mapper
    public interface HistoryNoteMapper {

        @Insert("<script>" +
                "INSERT INTO note (user_id, title, content, status, like_count, collect_count, comment_count, read_count, created_at) VALUES " +
                "<foreach collection='titles' item='t' separator=','>" +
                "(8888, #{t}, '历史正文', 2, FLOOR(RAND()*500), FLOOR(RAND()*100), FLOOR(RAND()*200), FLOOR(RAND()*10000), " +
                "DATE_SUB(NOW(), INTERVAL FLOOR(RAND()*7*24) HOUR))" +
                "</foreach>" +
                "</script>")
        int insertHistoryBatch(List<String> titles);
    }
}
