package com.example.note;

import com.example.note.like.DbLikeService;
import com.example.note.like.LikeReconcileJob;
import com.example.note.like.LikeSyncJob;
import com.example.note.like.RedisLikeService;
import com.example.note.note.entity.Note;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 验收：刷数据三件套 —— 增量同步 / 全量对账 / 分布式锁
 *
 * 测试姿势（定时任务的标准测法）：@Scheduled 的自动调度在测试环境关掉（见基类属性），
 * 直接注入 Job 调 public 方法 —— 这就是「任务业务方法务必可独立调用」的原因
 */
class LikeSyncE2ETest extends AbstractIntegrationTest {

    @Autowired
    private RedisLikeService redisLikeService;
    @Autowired
    private DbLikeService dbLikeService;
    @Autowired
    private LikeSyncJob likeSyncJob;
    @Autowired
    private LikeReconcileJob likeReconcileJob;
    @Autowired
    private RedissonClient redissonClient;

    /** 增量同步：Redis 计数 → 脏标记 → syncOnce 快照刷回 → 库里追平 */
    @Test
    void sync_once_flushes_dirty_counts_to_mysql() {
        Long noteId = insertNote("同步靶子", "Redis 是真相，库要追平", Note.STATUS_PUBLISHED);

        // 三个用户点赞：Redis count=3，脏集合有 noteId，库 count=0（Phase 2 欠下的债）
        redisLikeService.like(4001L, noteId);
        redisLikeService.like(4002L, noteId);
        redisLikeService.like(4003L, noteId);
        assertThat(noteMapper.selectById(noteId).getLikeCount()).isEqualTo(0);

        likeSyncJob.syncOnce();

        assertThat(noteMapper.selectById(noteId).getLikeCount())
                .as("syncOnce 后库里的数应追平 Redis 真相").isEqualTo(3);
        assertThat(redisLikeService.readCount(noteId)).isEqualTo(3);
    }

    /** 快照幂等：同一轮脏数据重复刷（模拟 Job 重跑/多实例边缘情况），结果不漂移 */
    @Test
    void sync_is_idempotent_snapshot() {
        Long noteId = insertNote("幂等靶子", "刷一百遍结果一样", Note.STATUS_PUBLISHED);
        redisLikeService.like(4101L, noteId);

        likeSyncJob.syncOnce();
        likeSyncJob.syncOnce();   // 没有脏数据时第二轮是空转
        redisLikeService.like(4102L, noteId);
        likeSyncJob.syncOnce();
        likeSyncJob.syncOnce();

        assertThat(noteMapper.selectById(noteId).getLikeCount()).isEqualTo(2);
    }

    /** 全量对账：库存值被人改错（DBA 手滑/Bug），以 note_like 关系表为准修正 */
    @Test
    void reconcile_fixes_wrong_count_from_fact_table() {
        Long noteId = insertNote("对账靶子", "流水是对的，余额错了就重算", Note.STATUS_PUBLISHED);
        dbLikeService.like(4201L, noteId);
        dbLikeService.like(4202L, noteId);      // 事实：2 行关系 + count=2

        // 模拟事故：直接把库存值改飞
        Note wrong = noteMapper.selectById(noteId);
        wrong.setLikeCount(99);
        noteMapper.updateById(wrong);

        likeReconcileJob.reconcileOnce();

        assertThat(noteMapper.selectById(noteId).getLikeCount())
                .as("对账应以关系表（事实）为准修正计数").isEqualTo(2);
    }

    /**
     * 分布式锁的防重复证明：两个「实例」（线程模拟）同时抢锁，恰好一个进临界区。
     * 这是「扫描-处理型任务多实例不安全」的解药 —— LikeReconcileJob 的护身符
     */
    @Test
    void distributed_lock_admits_exactly_one_winner() throws Exception {
        RLock lock = redissonClient.getLock("test:job:only-one");
        lock.forceUnlock();   // 清理可能的历史残留

        CountDownLatch bothTried = new CountDownLatch(2);
        AtomicInteger winners = new AtomicInteger();

        Runnable instance = () -> {
            boolean acquired = false;
            try {
                acquired = lock.tryLock(0, 30, java.util.concurrent.TimeUnit.SECONDS);
                if (acquired) {
                    winners.incrementAndGet();
                    sleep(300);   // 在临界区里待一会，制造「另一个实例也想进」的时间窗
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (acquired && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
                bothTried.countDown();
            }
        };

        Thread instanceA = new Thread(instance, "instance-A");
        Thread instanceB = new Thread(instance, "instance-B");
        instanceA.start();
        instanceB.start();
        bothTried.await();

        assertThat(winners.get()).as("两个实例并发抢锁，恰好一个进临界区").isEqualTo(1);
        lock.forceUnlock();
    }
}
