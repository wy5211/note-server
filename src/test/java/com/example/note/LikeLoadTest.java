package com.example.note;

import com.example.note.like.DbLikeService;
import com.example.note.like.LikeFlushService;
import com.example.note.like.MqLikeService;
import com.example.note.like.RedisLikeService;
import com.example.note.note.entity.Note;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phase 2 压测课：三版点赞同台打擂，数字说话
 *
 * 默认不随 CI 跑（结果受机器状态影响，不适合做断言），手动开跑：
 *   ./mvnw test -Dtest=LikeLoadTest -Dload=true
 *
 * 压的是 service 层（绕开 HTTP/Tomcat），纯粹对比「存储链路」的差异 ——
 * 教学聚焦：同一段业务代码，换存储/链路后的吞吐差距。
 *
 * 每轮 100 线程 × 100 次 = 1 万次点赞，三版各打一组独立笔记（组间用户×笔记组合全新，
 * 保证每轮都是「真写」而不是幂等命中空转 —— 那是虚高的 QPS）
 */
@EnabledIfSystemProperty(named = "load", matches = "true")
class LikeLoadTest extends AbstractIntegrationTest {

    private static final int THREADS = 100;
    private static final int OPS_PER_THREAD = 100;

    @Autowired
    private DbLikeService dbLikeService;
    @Autowired
    private RedisLikeService redisLikeService;
    @Autowired
    private MqLikeService mqLikeService;
    @Autowired
    private LikeFlushService likeFlushService;

    @Test
    void benchmark_three_versions() throws Exception {
        // 三组靶子笔记 + 独立用户段，保证三轮都是全新 (user, note) 组合
        List<Long> notesV1 = insertNotes("V1", 100);
        List<Long> notesV2 = insertNotes("V2", 100);
        List<Long> notesV3 = insertNotes("V3", 100);

        System.out.printf("%n=========== Phase 2 点赞压测：%d 线程 × %d 次 ===========%n%n", THREADS, OPS_PER_THREAD);

        long qps1 = runRound("V1 直写 MySQL（每次 2 条 SQL，行锁+连接池竞争）",
                (userId, noteId) -> dbLikeService.like(userId, noteId), notesV1, 1_000_000L);

        long qps2 = runRound("V2 Redis 计数+位图（纯内存 O(1)）",
                (userId, noteId) -> redisLikeService.like(userId, noteId), notesV2, 2_000_000L);

        long qps3 = runRound("V3 Redis+MQ 攒批（入口只做内存+发消息）",
                (userId, noteId) -> mqLikeService.like(userId, noteId), notesV3, 3_000_000L);

        System.out.printf("%n=========== 结果汇总 ===========%n");
        System.out.printf("V1 直写库     : %,6d ops/s%n", qps1);
        System.out.printf("V2 Redis      : %,6d ops/s  (%.1fx)%n", qps2, (double) qps2 / qps1);
        System.out.printf("V3 Redis+MQ   : %,6d ops/s  (%.1fx)%n", qps3, (double) qps3 / qps1);
        System.out.printf("V3 攒批队列余量（落库在后台匀速进行）: %d 条%n", likeFlushService.pendingSize());
        System.out.printf("================================%n%n");

        // 给 V3 的攒批留几秒跑完落库，日志里能看到 flush 打点
        sleep(6_000);
    }

    /** 一轮压测：THREADS 个线程同时开跑，各打 OPS_PER_THREAD 次点赞，返回吞吐 */
    private long runRound(String label, LikeOp op, List<Long> notes, long userIdBase) throws Exception {
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger errors = new AtomicInteger();
        List<Long> latencies = new ArrayList<>(THREADS * OPS_PER_THREAD);

        for (int t = 0; t < THREADS; t++) {
            long userId = userIdBase + t;
            final int myIndex = t;   // lambda 只能捕获事实最终变量，循环变量 t 每轮变 → 复制一份
            Thread thread = new Thread(() -> {
                try {
                    startGate.await();   // 所有线程到齐再开跑（模拟洪峰齐射）
                    for (int i = 0; i < OPS_PER_THREAD; i++) {
                        long begin = System.nanoTime();
                        op.exec(userId, notes.get((myIndex * OPS_PER_THREAD + i) % notes.size()));
                        latencies.add((System.nanoTime() - begin) / 1_000);   // 微秒
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
            thread.start();
        }

        System.out.println(">>> " + label);
        long t0 = System.currentTimeMillis();
        startGate.countDown();
        done.await();
        long elapsed = System.currentTimeMillis() - t0;

        latencies.sort(Long::compareTo);
        long total = THREADS * OPS_PER_THREAD;
        // 百分位索引用实际样本数（失败请求没有 latency 记录）—— 越界教训：别拿理论值当下标
        int n = latencies.size();
        System.out.printf("    耗时 %,d ms | QPS %,d | avg %,d μs | p95 %,d μs | p99 %,d μs | 错误 %d%n%n",
                elapsed, total * 1000 / Math.max(elapsed, 1),
                n == 0 ? 0 : latencies.stream().mapToLong(Long::longValue).sum() / n,
                n == 0 ? 0 : latencies.get(n * 95 / 100),
                n == 0 ? 0 : latencies.get(Math.min(n - 1, n * 99 / 100)),
                errors.get());
        return total * 1000 / Math.max(elapsed, 1);
    }

    private List<Long> insertNotes(String tag, int count) {
        List<Long> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(insertNote(tag + " 压测#" + i, "压测靶子", Note.STATUS_PUBLISHED));
        }
        return ids;
    }

    @FunctionalInterface
    private interface LikeOp {
        void exec(long userId, long noteId);
    }
}
