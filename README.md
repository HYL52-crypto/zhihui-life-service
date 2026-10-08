# 智惠生活服务系统

一个「本地生活探店 + 优惠券秒杀 + AI 智能客服」的 Spring Boot 后端项目。

项目目标：把高并发场景最常见的一组问题（超卖、缓存一致性、限流、异步削峰、超时关单）
和 LLM 应用集成（Function Calling / 多轮会话记忆）在同一套代码里完整跑通。

---

## 一、技术栈

| 分类 | 选型 | 说明 |
|---|---|---|
| 基础框架 | Spring Boot 2.3.12 + JDK 8 | 与主流企业存量项目保持一致 |
| ORM | MyBatis-Plus 3.4.3 | 条件构造器、乐观锁更新 |
| 缓存 | Redis 5 + Caffeine | 分布式缓存 + 本地二级缓存 |
| 消息 | Kafka 3.9（KRaft 单机） | 异步下单、缓存失效补偿 |
| 分布式工具 | Redisson 3.13 | 分布式锁、全局 ID 生成 |
| AI | LangChain4j 0.35.0 + 智谱 GLM-4-Flash | Function Calling + Redis 会话记忆（厂商无关，可换任意 OpenAI 兼容模型） |
| 其他 | Hutool、Lombok、Spring AOP | |

---

## 二、九个核心实现点

### 1. 秒杀防超卖 + 一人一单（Lua 原子脚本）
`src/main/resources/seckill.lua`

库存校验、扣减、一人一单判定三条命令写在同一个 Lua 脚本里，由 Redis 单线程执行保证原子性，
彻底消除「判断通过 → 还没扣减 → 另一个请求也通过」的超卖窗口。

返回值约定：`0` 成功 / `1` 库存不足 / `2` 重复下单 / `-1` 库存未预热。

### 2. MQ 异步下单削峰（Kafka）
`config/KafkaConfig.java`、`listener/SeckillVoucherListener.java`

Lua 校验通过后只发一条 Kafka 消息就返回订单号，真正的落库 + 扣库存由消费者按数据库能承受的速度处理。
消息 key 用订单号保证同一订单进同一分区；采用手动提交 offset，处理成功才 ack。
消费者侧按订单 ID 做幂等，Kafka 的「至少一次」投递不会产生脏数据。

### 3. 未支付订单自动关单 + 乐观锁
`task/OrderCloseTask.java`、`service/impl/VoucherOrderServiceImpl.java`

每分钟扫描超时未支付订单。关单与「支付回调」存在并发，用 MyBatis-Plus 的
`lambdaUpdate().eq(status, 未支付)` 做乐观锁——谁先改到谁生效，后到的更新行数为 0 直接放弃。
关单成功后回滚 Redis 库存与「已下单用户集合」。

### 4. 缓存击穿：逻辑过期
`utils/CacheClient.java#queryWithLogicalExpire`

热点 key 不设真实 TTL，而是把过期时间放进 value（`RedisData.expireTime`）。
逻辑到期后先返回旧数据，同时抢互斥锁开独立线程重建缓存，用户无感知、数据库不被打穿。

### 5. 缓存穿透：空值缓存
查询不到数据时写入空字符串并设置短 TTL（`CACHE_NULL_TTL`），后续同样的无效 id 直接命中空值返回。

### 6. 缓存一致性：删缓存 + MQ 补偿重试 + TTL 兜底
`ShopServiceImpl#update()`、`listener/ShopCacheInvalidateListener.java`、`task/CacheRepairTask.java`

三层保险：

1. 更新数据库后同步删一次缓存（Cache Aside 的正确顺序：先改库，再删缓存），并 `hasKey` 校验；
2. 无论成败都发一条 Kafka 消息做「延迟双删」，消费者最多重试 3 次并退避等待；
3. 3 次仍失败写进 Redis 死信 list，由定时任务每 5 分钟兜底重删；
   同时所有缓存 key 都带「逻辑过期 + 真实 TTL（含随机抖动）」，最坏情况也会自动失效。

### 7. Caffeine + Redis 二级缓存
`config/CaffeineConfig.java`、`ShopServiceImpl#queryById()`

查询顺序：Caffeine 本地缓存 → Redis（逻辑过期）→ 数据库。
命中本地缓存不产生任何网络开销；写库时同步 `invalidate` 本地缓存，避免单实例内读到旧数据。

### 8. 滑动窗口限流（Redis ZSet + Lua + 自定义注解 + AOP）
`annotation/RateLimit.java`、`aspect/RateLimitAspect.java`、`resources/rate_limit.lua`

ZSet 存每次请求的时间戳，`zremrangebyscore` 清掉窗口外的记录，`zcard` 统计窗口内数量，超阈值拒绝。
比固定窗口计数器更平滑，不会出现窗口边界的 2 倍流量尖刺。
通过注解声明，支持 GLOBAL / IP / USER 三个维度（未登录时 USER 自动降级为 IP）。

### 9. AI 智能客服（LangChain4j + Function Calling + Redis 记忆）
`ai/`、`config/AiConfig.java`、`controller/AiChatController.java`

接入层做成**厂商无关**：所有提供 OpenAI 兼容协议的模型都能直接用，换模型只改
`application.yaml` 里 `ai.llm.api-key / base-url / model` 三个值，Java 代码不动。
默认用**智谱 GLM-4-Flash**（长期免费、国内直连）；其他可用组合见配置文件注释
（阿里百炼、硅基流动、DeepSeek 等）。

- **Function Calling**：`@Tool` 标注的 4 个业务方法（搜店 / 查详情 / 预约到店 / 查我的预约），
  由大模型自己决定要不要调、传什么参数；预约会真实写进 `tb_appointment` 表。
- **多轮记忆**：自己实现 `ChatMemoryStore` 把对话历史存 Redis，
  相比默认的内存版多了「重启不丢、多实例共享」的能力，会话 TTL 24 小时。
- **会话隔离**：接口方法上的 `@MemoryId` 是关键，没有它所有用户会共用同一份记忆。
- **优雅降级**：没配 API Key 时不创建模型 Bean，项目照常启动。

---

## 三、本地启动

### 依赖环境

| 组件 | 版本 | 端口 |
|---|---|---|
| JDK | 8 | - |
| MySQL | 8 | 3306 |
| Redis | 5 | 6379 |
| Kafka | 3.9（KRaft） | 9092 |

### 步骤

```bash
# 1. 建库并导入表结构
mysql -uroot -p dingping < src/main/resources/db/appointment.sql

# 2. 修改 application.yaml 里的数据库地址 / 账号 / 密码

# 3.（可选）启用 AI 智能客服
export AI_API_KEY=sk-xxxxxx         # 不配也能启动，只是 /ai/chat 不可用
#   换模型只需再改 application.yaml 里的 ai.llm.base-url 和 ai.llm.model

# 4. 打包并启动
mvn package -DskipTests
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar --server.port=8081
```

---

## 四、接口验证清单

```bash
BASE=http://localhost:8081

# 基础
curl $BASE/shop-type/list
curl "$BASE/shop/search?keyword=茶"

# 缓存预热（逻辑过期）+ 查询
curl "$BASE/shop/warm/1?seconds=1800"
curl $BASE/shop/1

# 二级缓存一致性：改一次店铺，观察缓存被删 + MQ 补偿
curl -X POST $BASE/shop -H "Content-Type: application/json" \
  -d '{"id":1,"name":"测试商家","typeId":1,"area":"陆家嘴","address":"xx路1号","openHours":"10:00-22:00"}'

# 秒杀（需要登录态 + 已预热库存）
curl -X POST "$BASE/voucher-order/seckill/1"

# 限流（同一用户 10 秒内第 6 次会被拒）
for i in $(seq 1 8); do curl -s -X POST "$BASE/voucher-order/seckill/1"; echo; done

# 超时关单 / 支付
curl -X POST "$BASE/voucher-order/close-timeout?minutes=30"
curl -X POST "$BASE/voucher-order/pay/1"

# AI 智能客服（需要配置 DEEPSEEK_API_KEY）
curl -X POST "$BASE/ai/chat?sessionId=demo&message=帮我找一家咖啡店"
curl -X POST "$BASE/ai/chat?sessionId=demo&message=帮我预约刚才那家，今晚6点，2个人"
curl -X POST "$BASE/ai/chat?sessionId=demo&message=我约了什么"
curl -X DELETE "$BASE/ai/chat/demo"
```

---

## 五、目录结构

```
src/main/java/com/hmdp
├── ai/          智能客服：Assistant 接口、@Tool 工具箱、Redis 会话记忆
├── annotation/  自定义注解（限流）
├── aspect/      限流切面
├── config/      Kafka、Caffeine、AI 模型装配、拦截器注册
├── controller/  REST 接口
├── dto/         传输对象（含缓存补偿消息）
├── entity/      数据库实体
├── interceptor/ 登录校验 + Token 刷新双拦截器
├── listener/    Kafka 消费者（秒杀下单、缓存失效补偿）
├── mapper/      MyBatis-Plus Mapper
├── service/     业务逻辑
├── task/        定时任务（超时关单、死信兜底）
└── utils/       缓存工具、Redis 常量、ID 生成器等
```
