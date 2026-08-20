# note-server「晒晒」

仿小红书的图文笔记内容社区 —— Java 学习项目 #3。

## 三项目能力版图

| | im-server | mall-server | note-server |
|---|---|---|---|
| 业务本质 | 实时通讯 | 交易一致性 | **内容 + 数据规模** |
| Redis | 缓存/会话 | 分布式锁/秒杀 | 计数器/ZSET/BitMap/写扩散 |
| 消息队列 | — | — | **RocketMQ 全家桶** |
| 数据库 | Flyway 入门 | 事务/防超卖 | **刷数据/在线迁移/大事务** |

## 学习主线：每个技术都对应一个你见过的产品现象

| 现象 | 背后的技术 | Phase |
|---|---|---|
| 发布秒回，状态「审核中」稍后变 | MQ 异步解耦 | 1 |
| 卡审核中 30 分钟自动进人工队列 | 延迟消息 | 1 |
| 消息重复投递不重复处理 | 消费幂等 + 死信队列 | 1 |
| 热帖点赞 10w 页面秒回 | Redis 计数 + MQ 削峰 | 2 |
| 运营报表点赞数对不上 | 定时任务批量刷数据 | 3 |
| 双实例定时任务跑了两次 | 分布式锁 | 3 |
| 关注页又快又全 | Feed 推拉结合 | 4 |
| 热榜每小时更新带「飙升」 | ZSET + 定时算分 | 4 |
| 500w 老笔记补话题标签 | 游标分批刷数 | 5 |
| 8000w 行表改字段不停机 | 影子表双写在线迁移 | 5 |
| 发笔记奖积分不错账 | 本地消息表 vs 事务消息 | 6 |
| 搜「周末露营」毫秒出结果 | Elasticsearch | 7(可选) |

## 快速开始

```bash
# 1. 起全栈（MySQL/Redis/RocketMQ NameServer+Broker+Console/App）
docker compose up -d

# ⚠️ 首次启动若 broker 反复退出：named volume 属主是 root，rocketmq 用户(3000)写不进
docker run --rm -v note-server_note-mq-store:/d1 -v note-server_note-mq-logs:/d2 \
  alpine chown -R 3000:3000 /d1 /d2
docker compose up -d

# 2. 本地跑测试（连 3309/6381，独立测试库 note_server_test）
./mvnw test

# 3. 打开控制台玩一下（Phase 1 的主战场）
open http://localhost:8180
```

## 环境约定（三项目端口梯队）

| 服务 | 端口 | im-server | mall-server |
|---|---|---|---|
| 应用 | **9092** | 9090 | 9091 |
| MySQL | **3309** | 3307 | 3308 |
| Redis | **6381** | 6379 | 6380 |
| RocketMQ NameServer | **9876** | — | — |
| RocketMQ Broker | **10909/10911/10912** | — | — |
| RocketMQ Console | **8180** | — | — |

⚠️ 换网络环境后 `rocketmq/broker.conf` 的 `brokerIP1` 要改成新的宿主机 IP
（`ipconfig getifaddr en0` 查），否则宿主机应用连不上 broker。

## Phase 进度

- [x] **Phase 0** 骨架 + 七张表 + 用户/笔记同步版 CRUD（测试 5/5 绿）
- [x] **Phase 1** RocketMQ 登场：异步改造「三秒发布接口」（测试 8/8 绿）
- [x] **Phase 2** 点赞三版迭代（测试 13/13 绿 + 压测报告）
- [x] **Phase 3** 刷数据第一课 + 定时任务（测试 17/17 绿）
- [x] **Phase 4** Feed 流推拉结合 + 热榜（测试 22/22 绿）
- [x] **Phase 5** 数据工程周：刷数据/在线迁移/事务传播（测试 30/30 绿）
- [ ] Phase 6 分布式事务：本地消息表 vs 事务消息
- [ ] Phase 7 (可选) Elasticsearch + ShardingSphere

## Phase 5 学到了什么（数据工程三重奏）

### 一、刷数据：500w 老笔记补话题标签

| 主题 | 落点 |
|---|---|
| 反面教材 | 一把 `UPDATE ... WHERE topic IS NULL`：巨事务/锁全表/undo 暴涨/主从延迟/中途 kill 回滚比执行还久 |
| 正确四要素 | 游标分批（`id > cursor LIMIT 2000`）/ 幂等（`WHERE topic IS NULL` 天然可重跑）/ 限流（批间 sleep）/ 断点续跑（游标写 Redis） |
| 造数 | `DataGenService` 多值 INSERT 批量（一条 SQL 2000 个 VALUES，网络往返摊薄两个数量级） |

### 二、在线迁移：手工版 gh-ost（note.title VARCHAR(100)→300）

```
1.建影子表+DDL(影子无流量随便改) → 2.开双写(同事务同步新行) → 3.存量分批拷贝(INSERT IGNORE
跳过双写行) → 4.校验(COUNT+抽样内容) → 5.原子切换(RENAME TABLE 一条命令无中间态) → 6.观察后DROP
```

- 为什么不直接 ALTER：DDL 三档速度 instant（加列秒级）/ inplace / **copy（改列类型必须走）**
  —— 8000w 行 copy = 锁写小时级事故
- 双写与存量拷贝的重叠：`INSERT IGNORE` 先到者赢（gh-ost 同款竞态处理）
- 手工版的代码侵入（NoteService 挂双写钩子）正是工具的价值：gh-ost 用 binlog 做增量，业务零改动

### 三、事务进阶

| 主题 | 落点 |
|---|---|
| REQUIRES_NEW | 审计日志「主业务回滚我也要活」—— 迁移步骤日志同款（学以致用） |
| NESTED | 保存点「部分回滚」：外层存活、内层消失；与 REQUIRES_NEW 的物理事务区别 |
| 失效现场 1 | 自调用绕过代理（实证：日志陪葬）；修法 = ObjectProvider 注入自身代理 |
| 失效现场 2 | 异常被 catch 吞（实证：照常提交）；修法 = setRollbackOnly() |
| 失效现场 3 | private 方法无法被子类重写（实证：事务压根没开） |
| 大事务 | Phase 0 病灶回顾：事务内只做 DB 写，慢操作移出事务或移出请求 |

## Phase 5 踩坑档案

15. **`@Transactional` 的注解属性写法**：`@Transactional(Propagation.REQUIRES_NEW)` 编译不过 ——
    value() 是 String（事务管理器名），传播行为要写 `propagation = ...` 命名属性。
    该语法错还级联出「Lombok 生成方法找不到符号」的全套假错 —— 注解处理一轮失败殃及全轮
16. **自注入循环依赖**：构造器注入自身 Bean 会 `BeanCurrentlyInCreation`；`@Lazy` 放字段上
    不会被 Lombok 复制到构造参数（等于没加）—— `ObjectProvider<T>` 延迟解析是零配置正解
17. **包私有方法的事务行为有版本差异**（实测被回滚了）：教学换 private（Spring 文档钦定失效），
    失效演示要选「文档保证」的场景，别选行为摇摆的

## Phase 4 学到了什么

| 主题 | 落点 |
|---|---|
| 推拉结合 | 素人发笔记写扩散进粉丝收件箱（ZSET inbox，score=noteId）；大 V（粉丝>1000）不推，读时现查 —— `FeedPushService` / `FeedService` 两路合并 |
| 收件箱设计 | ZSET 有界队列（保留最近 1000 条防膨胀）；pipeline 批量投递 |
| 写时宽松读时严格 | 推扩散不等审核完成（消费时序早于审核），驳回笔记靠读时 filter —— 微博对删除/屏蔽内容的同款姿态 |
| 游标分页 | `nextCursor`（noteId）代替页码：Feed 活水下页码会重复/漏，cursor 永远稳；SQL 的 `id < cursor` 与 ZSET 的 `score < cursor` 同一心思 |
| 热榜 | `RankingJob` 每 5 分钟：互动加权（收藏3>评论2>赞1）/ 年龄衰减 `(h+2)^1.5`，快照式重写 ZSET，读接口毫秒级 |
| HyperLogLog | 阅读量 UV：12KB 固定空间估算任意基数，0.81% 误差 —— 「精度换空间」谱系（BitMap 精确 → HLL 近似） |
| 解耦复利 | FeedPushConsumer 是 note-publish 的第三个消费组，发布方零改动兑现 Phase 1 承诺 |

**热榜公式的手感**（注释里有完整心算）：10 万赞的 3 天旧帖（314 分）险胜 300 赞的新帖（212 分）——
时间衰减不是杀死旧内容，而是让新内容有出头之日。

## Phase 4 踩坑档案

11. **`ZSetOperations.reverseRangeByScore` 只有 double 闭区间重载**（3.5.0 亲测，Range/String
    版不在该接口上）：开区间语义用「整数 score 下 `max = cursor - 0.5`」数学等效换算
12. **游标闭区间导致翻页重复**：cursor 那条会原样出现在下一页第一条 —— 上面换算的由来，
    测试 `feed_pagination_uses_cursor_without_overlap` 抓出来的
13. **IDE 的 ECJ 与 Maven javac 混编 target**：IDE 后台把带「编译占位 Error」的类写进
    target/classes，运行时炸 `Unresolved compilation problems` 且干扰增量编译判断 ——
    症状诡异时 `mvnw clean test` 原子跑一遍再下结论
14. **热榜/排序类测试要考虑历史数据污染**：压测遗留的 300 篇笔记分数会霸榜 ——
    造数要么量级碾压要么测试内清场，排序断言才可信

## Phase 3 学到了什么

**还债主线**：Phase 2 让 Redis 当了计数真相源，MySQL 严重落后 —— 本 Phase 用「增量同步 +
全量对账」双层机制把库里的数追平，这是所有「缓存放写、库兜底」架构的标准闭环。

| 主题 | 落点 |
|---|---|
| 增量刷新 | 点赞时 `SADD` 脏集合，`LikeSyncJob` 每 30s `SPOP` 批量弹出 → `MGET` → CASE WHEN 快照刷回 |
| 刷 vs 攒的语义差 | 快照覆盖（`= value`，幂等）vs 增量累加（`+ delta`）—— 两种刷数据模式的分水岭 |
| 全量对账 | `LikeReconcileJob` 凌晨 4 点 cron：以 note_like 关系表（事实）核对并修正 like_count（余额）|
| 游标分页 | `WHERE id > lastId LIMIT 500` 深翻页等成本 —— Phase 5 刷 500w 行的预演 |
| 分布式锁 | Redisson `tryLock(0, 600, SECONDS)`：抢不到就走。「SPOP 原子取走型任务不用锁、扫描处理型必须锁」的辨析 |
| cron 表达式 | `0 0 4 * * ?` 首次出场（秒分时日月周；日周冲突让位用 `?`）|
| Redis 持久化 | compose 开 AOF + 数据卷：RDB 快照 vs AOF 追加日志的取舍；持久化 ≠ 高可用 |
| 幂等方案三号 | 快照覆盖（前有：条件更新状态机、affected 锚定增量）—— 凑齐三种姿势 |

**对账的哲学**（账务系统通用）：流水（note_like）永远是对的，余额（like_count）错了就从流水重算。
增量同步管效率，全量对账管兜底 —— 少了后者，任何一次进程崩溃/手滑改库都会永远错下去。

**定时任务的测试姿势**：自动调度在测试环境关掉（`note.job.like-sync-auto=false`），
直接注入 Job 调 public 方法断言 —— 所以任务的业务方法务必可独立调用（也好运维手动补跑）。

## Phase 2 学到了什么

**演进主线**：一个点赞接口，三版实现共存，配置切换（`note.like.mode`），压测数字说话。

| 版本 | 实现 | QPS | p99 | 一句话 |
|---|---|---|---|---|
| V1 `v1_db` | 每次点赞 2 条 SQL（关系+计数） | 1,275 | 389ms | 行锁串行 + 连接池排队，热帖必挂 |
| V2 `v2_redis` | INCR 计数 + BitMap 是否赞过 | 8,733 (6.8x) | 78ms | 快是快，库里的数永远落后 |
| V3 `v3_mq` | Redis 秒回 + MQ 攒批落库 | 4,158 (3.3x) | 98ms | 洪峰入队，下游匀速 |

（数字为教学机本地量级参考；V3 比 V2 慢是每次多付一次 `syncSend` 同步确认——真实高吞吐换
异步发送即可逼近 V2，吞吐与可靠性的选择题）

**削峰的数学证明**（压测日志原文）：1 万条点赞消息 → 2 轮 flush → 每轮几条批量 SQL
（`INSERT IGNORE` 按笔记分组 + `CASE WHEN` 一条摊平计数），对比 V1 的 2 万条独立 SQL。

| 主题 | 落点 |
|---|---|
| 首次引入 XML Mapper | `NoteLikeMapper.xml`：批量 INSERT IGNORE / 行构造器 DELETE / CASE WHEN 批量 UPDATE |
| 幂等之锚 | affected 返回值是唯一可信源（uk_user_note 兜住消息重复投递） |
| BitMap | 500w 用户一篇笔记的点赞记录 ≈ 625KB |
| 削峰填谷 | `LikeFlushService`：内存队列 + 2s 定时 drain + 分组批量落库 |
| @EnableScheduling | 定时任务第一次亮灯（Phase 3 的前哨） |
| 降级复用 | MQ 发送失败 → 退化为 V1 直写（数据不丢） |

跑压测：`./mvnw test -Dtest=LikeLoadTest -Dload=true`（默认不进 CI，结果受机器状态影响）

## Phase 2 踩坑档案

8. **javadoc 里写 ant 通配符路径**（如 `classpath` 后跟 `mapper/**/x.xml`）：其中的 `*/` 会把
   注释提前闭合，后面的中文全变「非法字符 ）」—— 编译器报这个错先想注释截断
9. **Spring Data Redis 的 `setBit` 没有两参重载**：置位必须 `setBit(key, offset, true)`，
   旧值从返回值拿（`Boolean`）；漏传 value 参数编译才炸
10. **新 topic 冷启动再现**：note-like 首测 15s 断言等不到消费——老朋友 30s 路由刷新。
    本次用生产正解收尾：`mqadmin updateTopic` 预建 topic（见下方命令）

```bash
# 预建 topic（生产环境的正确姿势：Topic 管控，不靠 autoCreate）
docker exec note-mq-broker sh -c 'sh mqadmin updateTopic -n note-mq-namesrv:9876 -c DefaultCluster -t note-like'
```

## Phase 1 学到了什么

**改造主线**：发布接口从「同步审核+同步压图（带图 RT ≥600ms）」变成「事务落库 → commit 后发事件 → 秒回」，
审核和图片处理各自成为独立消费组后台消化 —— 用户看到的状态从「盯着转圈」变成「发完即见（审核中）」。

| 主题 | 落点 |
|---|---|
| 事务提交后发消息 | `NoteEventProducer.publishAfterCommit`（防「消费者查库扑空」） |
| 发送失败降级 | `NoteEventProducer.degrade`（MQ 挂了本地同步兜底） |
| 延迟消息 | 发布时发 30s 延迟检查，卡「审核中」自动转人工（status=4） |
| 幂等方案 A：条件更新 | `ReviewService.review`（WHERE status=1 天然幂等，零额外设施） |
| 幂等方案 B：Redis SETNX | `ImageProcessService`（含「失败要还标记」的经典坑处理） |
| 消费失败 → 重试 → 死信 | 图片消费者对标题含 `poison` 的笔记抛异常，走完整重试链路 |
| 死信告警 | `DlqAlarmConsumer` 盯 `%DLQ%note-image-group` |

**动手观察死信链路**：发一篇标题带 `poison` 的笔记，然后开 http://localhost:8180
→ Topic 里搜 `%RETRY%note-image-group`（重试中）和 `%DLQ%note-image-group`（16 次重试失败后的遗骸）。
注意：重试只在消费者在线时推进——本地把应用跑起来它才会继续走完。

## Phase 1 踩坑档案

6. **rocketmq-spring 带 delayLevel 的 `syncSend` 重载**收的是 Spring Messaging 的 `Message`，
   要 `MessageBuilder.withPayload(...).build()` 包装，直接传对象编译不过
7. **消费者先于 topic 就绪的时序坑**：autoCreateTopicEnable 模式下 topic 由 producer 首发消息创建，
   先启动的消费者拿不到路由，要等 30s 刷新周期 —— 首次跑测试会「莫名超时 10s+」，
   topic 建好后永久存在，之后都是秒级。生产正解：topic 预先建好 + autoCreateTopicEnable=false

## Phase 0 踩坑档案（都有教学价值）

1. **RocketMQ dashboard 镜像组织名是 `apacherocketmq/rocketmq-dashboard`**，不是 `apache/`（后者 404）
2. **新版 dashboard 容器内监听 8082**（老版 8080），端口映射要对准
3. **named volume 属主 root + 镜像内非 root 用户** → broker 初始化静默失败，只在 shutdown 时炸 NPE；修法 chown 3000:3000 + compose 显式 `user: "3000:3000"`
4. **`depends_on` 不等就绪**：MySQL 首次初始化 20s+，app 一起动就连库失败；`healthcheck` + `condition: service_healthy` 才是正解
5. **宿主机端口约定泄漏进容器网络**：application.yml 的 `redis.port: 6381`（宿主机映射）被容器内 app 继承 → 连 `redis:6381` 被拒；容器内互通走标准端口 6379，环境变量要连 PORT 一起覆盖
