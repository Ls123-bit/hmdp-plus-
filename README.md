


#郑重说明：项目的好多设计亮点请移步 Gitee 阿星不是程序员  https://gitee.com/shining-stars-l  本项目只是一个二次开发的demo 

# 黑马点评-plus（hmdp-plus）

> 基于经典项目「黑马点评」的升级重构版：**多模块工程化 + 分库分表 + 高并发秒杀闭环 + 开源大模型智能客服**。
> 技术栈：Spring Boot 3 · Java 17 · MyBatis-Plus · ShardingSphere · Redisson · Kafka · Vue 3 + Vite + Element Plus · Ollama

## ✨ 项目亮点

### 架构与工程化
- 多模块 Maven 工程，业务、缓存、分片、消息、ID 生成等职责分离
- ShardingSphere-JDBC 分库分表：两库存储，订单/用户/券等核心表按分片键取模拆分，广播表保证基础数据一致
- 自研 Redis 缓存框架：统一处理缓存穿透（布隆过滤器）、击穿（逻辑过期）、雪崩
- Redisson 分布式锁防超卖；号段 + 雪花算法分布式 ID

### 高并发秒杀
- Lua 脚本预扣库存 + 一人一单校验，Kafka 异步落库削峰
- 消息可靠性闭环：幂等生产、手动提交、死信队列、订单路由、定时对账
- 动态令牌 + 接口限流 + 候补排队 + 订阅通知

### 社交与本地生活
- 点赞、关注/共同关注、Feed 流收件箱滚动分页
- 签到 BitMap、月度统计、UV 统计（HyperLogLog）、附近商户 GEO

### 智能化与体验
- 🤖 **智能商户推荐客服**：接入 Ollama 开源大模型（Qwen2.5），基于商户真实数据生成推荐；支持品类、预算、商圈、评分等多维意图识别；大模型离线时自动降级规则引擎，功能始终可用
- 🗺️ **附近商户页**：商户分布示意图、商圈筛选、浏览器定位距离排序
- 💬 **评论系统**：笔记评论列表与发表，评论数实时同步
- 🏪 **商户关注**：商户详情页一键关注/取消
- ✏️ **作品管理**：个人主页笔记编辑/删除，首页实时同步
- 👤 **主页增强**：关注/粉丝真实计数、个性签名在线编辑
- 🧭 **模拟导航 / 呼叫**：商户页一键体验导航路线与模拟来电

## 🗄️ 数据库设计

- 双库分片：`hmdp_0` / `hmdp_1`
- **分片表**：订单、秒杀券、用户、用户信息、用户手机号、券、订单路由、对账日志（按用户/订单/券等分片键取模，物理表 `_0/_1` 后缀）
- **广播表**：笔记、评论、关注、商户、商户类型、签到等基础数据（双库同构）
- 新增商户关注表：

```sql
CREATE TABLE IF NOT EXISTS `tb_shop_follow` (
  `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`     BIGINT   NOT NULL COMMENT '用户id',
  `shop_id`     BIGINT   NOT NULL COMMENT '商户id',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '关注时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_shop` (`user_id`, `shop_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商户关注表';
```

完整建库建表脚本见 [`sql/hmdp_plus_schema.sql`](sql/hmdp_plus_schema.sql)，演示数据见 [`sql/hmdp_0.sql`](sql/hmdp_0.sql) / [`sql/hmdp_1.sql`](sql/hmdp_1.sql)。

## 🧰 环境要求

| 中间件 | 版本 | 必需 |
| --- | --- | --- |
| JDK | 17+ | ✅ |
| MySQL | 8.0+ | ✅ |
| Redis | 7.x | ✅ |
| Kafka | 3.6+（KRaft） | 建议 |
| Ollama | 0.3+（可选，未部署自动降级） | 可选 |
| Node.js | 18+ | 前端 |

中间件建议部署在本地或自建虚拟机（Docker 均可），后端通过配置文件或启动参数指向对应地址。

## 🚀 快速开始

```bash
# 后端
mvn clean package -DskipTests -pl hmdp-core-service -am
java -jar hmdp-core-service/target/hmdp-core-service-0.0.1-SNAPSHOT.jar

# 前端
cd hmdp-vue3
npm install
npm run dev   # http://localhost:5173
```

- 数据库连接与分库分表配置见 `hmdp-core-service/src/main/resources`（请按自己的环境修改连接地址与账号）
- 智能客服配置见 `application.yml` 的 `assistant` 段（Ollama 地址与模型名）
- 登录使用验证码方式，开发模式下验证码会直接返回

## 📌 目录结构

```
hmdp-plus
├── hmdp-core-service          # 核心业务服务
├── hmdp-sharding              # 分库分表配置
├── hmdp-redisson-framework    # 分布式锁
├── hmdp-redis-tool-framework  # 缓存工具框架
├── hmdp-id-generator-framework# 分布式 ID
├── hmdp-mq-framework          # 消息队列
├── hmdp-parameter             # 参数校验
├── hmdp-common                # 通用模块
├── hmdp-vue3                  # 前端
└── sql                        # 建库建表脚本
```
