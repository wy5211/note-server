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
- [ ] Phase 2 点赞三版迭代（压测驱动）
- [ ] Phase 3 刷数据第一课 + 定时任务
- [ ] Phase 4 Feed 流推拉结合 + 热榜
- [ ] Phase 5 数据工程周：刷数据/在线迁移/事务传播
- [ ] Phase 6 分布式事务：本地消息表 vs 事务消息
- [ ] Phase 7 (可选) Elasticsearch + ShardingSphere

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
