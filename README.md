# FoodieGo

## Project Overview

FoodieGo is a local food review and flash-deal platform backend built on Spring Boot. It provides
shop discovery, voucher flash sales (seckill), user social features, and more.

## Tech Stack

- **Language**: Java 8+
- **Framework**: Spring Boot 2.x
- **Database**: MySQL 5.7+
- **Cache**: Redis 6.x + Redisson
- **Message Queue**: RabbitMQ
- **ORM**: MyBatis Plus
- **API Docs**: Swagger

## Project Structure

```
src/main/java/com/foodiego/
├── FoodieGoApplication.java       # Application entry point
├── controller/                    # REST controllers
│   ├── UserController.java        # User management
│   ├── ShopController.java        # Shop management
│   ├── VoucherController.java     # Voucher management
│   ├── VoucherOrderController.java# Flash-sale orders
│   ├── BlogController.java        # Blog/social feed
│   ├── BlogCommentsController.java# Comments
│   ├── FollowController.java      # Follow/unfollow
│   ├── ShopTypeController.java    # Shop categories
│   └── UploadController.java      # File upload
├── service/                       # Business service layer
├── mapper/                        # Data access layer
├── entity/                        # Entity classes
├── dto/                           # Data transfer objects
├── config/                        # Configuration classes
└── utils/                         # Utility classes
```

## Quick Start

### Prerequisites

- JDK 1.8+
- Maven 3.6+
- MySQL 5.7+
- Redis 6.x+
- RabbitMQ 3.8+

### Configuration

1. Create the database and run the init script:

```sql
CREATE DATABASE foodiego CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

Execute `src/main/resources/db/hmdp.sql`

2. Update database, Redis, and RabbitMQ connection settings in `src/main/resources/application.yaml`

### Running the Application

**Development mode:**

```bash
mvn spring-boot:run
```

**Build & package:**

```bash
mvn clean package
java -jar target/foodiego-0.0.1-SNAPSHOT.jar
```

**Docker deployment:**

```bash
mvn clean package -DskipTests
docker compose up -d
```

Docker builds the packaged JAR. MySQL imports `db/hmdp.sql` only when the database
volume is empty; existing volumes are preserved.

## Core Features

| Module | Feature | Description |
|--------|---------|-------------|
| User | Login/Register, Profile | Token-based stateless auth |
| Shop | Shop CRUD, Categories | Redis cache-optimized |
| Voucher | Regular & Flash-sale Vouchers | Time-window restrictions |
| Seckill | High-concurrency flash sales | Redis + Lua atomicity |
| Social | Follow, Blog, Comments | Scroll-based pagination |

## API Documentation

After startup, visit: `http://localhost:8081/swagger-ui.html`

## Key Features

- **Distributed Lock**: Redisson-based, supports reentrant & fair locks
- **Cache Strategy**: Redis cache with TTL, logical expiration, and active invalidation
- **Seckill Optimization**: Lua scripts ensure atomic stock deduction
- **Message Queue**: RabbitMQ for async order processing
- **Rate Limiting**: Sentinel flow control

## 正确性修复与部署说明（2026-10-19）

- 下单、上传、签到和用户接口需要 `Authorization: <token>`。商铺浏览、分类、
  优惠券列表、热门笔记、静态页面和 `/actuator/health` 保持公开。
- 新增/修改商铺、创建优惠券、删除上传图片需要管理员身份。通过
  `FOODIEGO_ADMIN_USER_IDS=1,2` 配置已有用户 ID；默认为空，拒绝所有管理写操作。
  这是当前单体项目的管理员白名单，不包含商家资源归属模型。
- 请求开始时清除残留用户上下文，请求完成（包括异常和权限拒绝）后清理，防止线程复用串号。
- 秒杀时间按 `FOODIEGO_SECKILL_ZONE_ID` 解释，默认 `Asia/Shanghai`；
  Lua 使用 Redis 时钟判断 `[开始时间, 结束时间)`。数据库提交后才原子发布库存和时间元数据，
  保留到活动结束后一天。缺少元数据或库存时拒绝抢购，不自动重建库存。
- 消费者在数据库事务内锁定活动记录、检查订单，再扣库存并保存；保存失败回滚库存。
  唯一索引约定同一用户、同一优惠券最多一笔订单，取消后重购不在本次实现范围内。
- 消费失败最多执行四次（首次加三次退避重试），之后进入死信队列。
  死信先提交数据库失败记录，再通过 Lua 幂等回补；成功订单不会回补，
  迟到的失败订单消息不会重新创建订单。补偿失败保留消息并重试。
- Redisson 与 Spring Redis 共用连接配置。Compose 为 Redis 配置密码、AOF 和数据卷，
  为上传目录配置持久卷。开发模式上传目录为 `./uploads`，可用
  `FOODIEGO_UPLOAD_DIR` 覆盖，图片通过 `/imgs/**` 访问。

### 已有数据库升级

1. 暂停抢购入口并等待已接受的订单消费完毕。
2. 备份数据库，先检查重复的 `(user_id, voucher_id)`：
   `src/main/resources/db/migrations/20261019_order_idempotency.sql` 开头包含检查 SQL。
3. 根据业务记录处理重复数据后，在旧库执行该增量脚本一次，添加唯一索引和失败记录表。
   不要对已有数据库重新执行包含 `DROP TABLE` 的 `hmdp.sql`。
4. 配置管理员 ID 后启动新版本。旧的正在进行的活动若缺少
   `seckill:info:<券ID>` 元数据会拒绝抢购；需要从数据库活动时间补充
   `begin_time`、`end_time`（上述时区对应的 Unix 毫秒），不要重置已售活动的库存。
   新建活动会自动发布这些数据。

若数据库已提交但 Redis 发布失败，创建接口会报错，活动保持不可抢购，需要排查 Redis
后补充活动缓存。本次修复消费幂等与补偿，尚未实现 Redis 预扣到 MQ 投递的可靠事件日志，
也不保证这一阶段的进程崩溃恢复；这是后续可靠投递迭代的范围。

### 回归验证

使用 JDK 8 运行无需 Docker 的测试（H2 验证真实事务、约束和行锁）：

```bash
mvn test -Dtest=AuthenticationWebTest,OrderTransactionTest,VoucherPublicationTest,UploadControllerTest,RabbitMQHandlerTest,VoucherOrderMQServiceImplTest,CacheClientTest,RedisIdWorkerTest,ShopServiceImplTest,UserServiceImplTest,BlogServiceImplTest,FollowServiceImplTest
```

Lua 测试连接本机独立的 Redis 测试实例，通过环境变量指定端口，不清空共享数据库：

```bash
FOODIEGO_TEST_REDIS_PORT=16381 mvn test -Dtest=SeckillLuaTest
```

有 Docker 时可执行 MySQL、Redis 和 RabbitMQ 的完整集成测试：

```bash
mvn test -Dtest=LoginIntegrationTest,ShopCacheIntegrationTest,SeckillIntegrationTest
```

秒杀集成测试同时核对 Redis 库存、异步落库订单数和 MySQL 库存。H2 测试不能替代
MySQL 的实际隔离级别与锁行为验证；部署前仍应在目标基础设施上执行集成测试。
