# Durable Job Lease Store（本地可持久化的后台任务租约与状态流转）

一个只依赖本地运行环境的后台任务调度内核：任务创建 / 游标分页查询、领取-续租-完成的租约生命周期、
乐观并发控制、幂等结果提交、租约超时自动回收、失败指数退避与重试上限。
**不依赖外部数据库、消息队列或第三方服务**——持久化使用本地 JSON 快照文件（原子落盘），重启自动恢复。

技术栈：Java 21、Spring Boot 3.4（Web + Scheduling + Configuration Properties）、Jackson、JUnit 5。

---

## 1. 快速开始（本地验证）

```bash
# 运行全部测试（14 个，含并发、重复提交、租约回收、非法流转、游标边界、HTTP 端到端、重启恢复）
mvn test

# 启动服务（默认 http://localhost:8080，快照文件 data/task-store.json）
mvn spring-boot:run

# 想快速观察租约回收，可用短租约参数启动
mvn spring-boot:run -Dspring-boot.run.arguments="\
  --server.port=8089 --durable.lease-ttl=5s --durable.reaper-interval=2s \
  --durable.backoff-base=1s --durable.store-file=data/smoke.json"
```

### 典型调用序列

```bash
B=http://localhost:8080/api/tasks

# 1. 创建任务
curl -s -X POST $B -H 'Content-Type: application/json' -d '{"payload":"job-a"}'

# 2. 领取指定任务（或 POST /api/tasks/claim-any 领取任意可派发任务）
curl -s -X POST $B/claim -H 'Content-Type: application/json' \
  -d '{"workerId":"worker-1","taskId":"<taskId>"}'
# -> status=RUNNING，并返回 leaseToken 与 leaseExpiresAt

# 3. 长任务续租
curl -s -X POST $B/<taskId>/renew -H 'Content-Type: application/json' \
  -d '{"workerId":"worker-1","leaseToken":"<token>"}'

# 4a. 提交成功
curl -s -X POST $B/<taskId>/complete -H 'Content-Type: application/json' \
  -d '{"workerId":"worker-1","leaseToken":"<token>","succeeded":true}'
# 4b. 提交失败（进入退避重试；次数用尽则 FAILED）
curl -s -X POST $B/<taskId>/complete -H 'Content-Type: application/json' \
  -d '{"workerId":"worker-1","leaseToken":"<token>","succeeded":false,"errorMessage":"boom"}'

# 5. 查询：游标分页
curl -s "$B?limit=20"
# -> { items:[...], nextCursor:"...", hasMore:true }
curl -s "$B?limit=20&cursor=<nextCursor>"
```

---

## 2. 状态模型

```
                 claim()
  PENDING ───────────────────────► RUNNING
     │                              │ │ │
     │ cancel()                     │ │ │ complete(succeeded)
     │                              │ │ └──────────────► SUCCEEDED（终态）
     │                              │ │
     │                              │ │ complete(failed)，attempts < max
     │◄─────────────────────────────┘ │  （带 notBefore 指数退避）
     │                                │
     │                                │ complete(failed)，attempts >= max
     │                                └──────────────────► FAILED（终态）
     │
     │ 租约超时被回收（Reaper 定时扫描）
     │◄──── RUNNING（attempts < max，退避后重派）
     └───── RUNNING（attempts >= max）─────────────────► FAILED（终态）

  PENDING / RUNNING(租约已过期) ──cancel()──► CANCELLED（终态）
```

- **PENDING**：待领取。若处于失败退避期（`notBefore > now`），拒绝领取，直到退避结束。
- **RUNNING**：被某执行者持有租约。租约到期前他人无法领取。
- **SUCCEEDED / FAILED / CANCELLED**：终态，拒绝一切续租、结果提交、取消。
- 只有上图列出的流转合法，其余一律拒绝并返回明确错误码（见第 5 节）。
- 取消语义：`PENDING` 可直接取消；`RUNNING` 在**租约仍有效**时取消被拒绝（避免误杀在跑的任务），
  租约过期后可取消；已 `CANCELLED` 的任务不会被回收器重派。

### 失败重试上限与退避

- 每次 `claim()` 成功，`attemptCount + 1`；失败提交与租约超时回收都计入尝试次数。
- 当 `attemptCount >= durable.max-attempts`（默认 3）再次失败/超时 → 任务置 **FAILED**，不再派发。
- 否则回到 `PENDING` 并设置 `notBefore = now + 退避`：
  `backoff = min(backoff-base × backoff-multiplier^(attempt−1), backoff-max)`，默认 `2s, 4s, 8s …，上限 60s`。

---

## 3. 租约与幂等规则

### 领取（claim）

- 领取是存储层**单 key 锁内的“状态校验 + 写入”原子操作**（`ConcurrentHashMap.compute`）。
- 两个执行者并发领取同一任务：恰好一个成功；另一个得到 **409 CONCURRENT_MODIFICATION**，
  不会出现重复执行，也不会静默丢更新。

### 续租（renew）与完成（complete）的身份校验顺序

请求必须同时携带 `workerId` 与领取时签发的 `leaseToken`，按以下顺序判定，错误原因彼此可区分：

| 情况 | errorCode | HTTP |
|---|---|---|
| 任务已是终态（SUCCEEDED/FAILED/CANCELLED） | `TERMINAL_STATE` | 409 |
| 租约时间已过期（`leaseExpiresAt <= now`） | `LEASE_EXPIRED` | 409 |
| 令牌/持有者不匹配（被他人接管、旧令牌、伪造令牌），或当前非 RUNNING 却来续租/提交 | `LEASE_CONFLICT` | 409 |
| 终态任务 + **同一 worker、同一 leaseToken、相同结果**再次提交 | `DUPLICATE_SUBMISSION` | 409 |
| 终态任务 + 不同 worker / 不同令牌 / 不同结果 | `TERMINAL_STATE` | 409 |

- 租约被回收后任务重新派发会**签发新的 leaseToken**；旧持有者即使网络延迟恢复后再续租/完成，
  也会因令牌不匹配得到 `LEASE_CONFLICT`，无法覆盖新持有者的执行。
- 成功完成时保留 `workerId / leaseToken`，专门用于识别“同一结果的重复提交”：
  网络重试导致的重复 POST 得到 `DUPLICATE_SUBMISSION`，状态保持首次结果不变。

### 租约回收（LeaseReaper）

- `@Scheduled` 定时器（`durable.reaper-interval`，默认 5s）扫描 `RUNNING && leaseExpiresAt < now`。
- 回收时在锁内**双重检查**，防止持有者在最后一刻续租/完成后被误回收。
- 回收 = 一次超时失败：`timeoutCount++`，按退避重派；次数用尽置 FAILED。
- 测试中可直接注入服务调用 `reapExpiredLeases()`，并通过可控时钟精确制造过期，无需 sleep。

---

## 4. 游标语义（稳定顺序分页）

- **键集（keyset）分页**：任务 ID 是“毫秒时间戳前缀 + 128 位随机后缀”的时间有序唯一串，
  列表固定按 ID 升序。请求 `?cursor=<token>&limit=N`，服务端只取 `id > 锚点ID` 的前 N 条，
  并多取 1 条判断 `hasMore`，用本页最后一条的 ID 签发下一页游标。
- 因为查询条件是“严格大于锚点”而非基于偏移量：
  - 翻页期间**新增**记录：ID 落在尾部只会出现在后续页，不会让当前窗口错位；
  - 翻页期间**删除**记录：不会造成条目前移/后移导致的重复或漏读；
  - 所有页的条目并集 = 恰好每个现存条目一次。
- 游标是**不透明签名令牌**：`base64url("anchorId|issuedAt") + "." + HMAC-SHA256(payload, secret)`。

| 游标问题 | 判定依据 | errorCode | HTTP |
|---|---|---|---|
| 格式非法 / payload 改动 / HMAC 不匹配（被篡改、伪造） | 签名校验失败 | `CURSOR_INVALID` | 400 |
| 签名合法，但距签发时间超过 `durable.cursor-ttl`（默认 10 分钟） | `now - issuedAt > ttl` | `CURSOR_EXPIRED` | 400 |
| 签名合法且未过期，但锚点任务已被删除 | 存储中查不到 anchorId | `CURSOR_ANCHOR_DELETED` | 400 |

三种失败原因互不混淆；调用方对任一错误的恢复方式都是**丢弃游标、从第一页重新开始**。

---

## 5. 错误码总表

所有错误返回统一 JSON：`{ "errorCode": "...", "message": "...", "timestamp": "..." }`，
调用方应按 `errorCode`（而非 message 文案）分支处理。

| errorCode | 含义 | HTTP |
|---|---|---|
| `TASK_NOT_FOUND` | 任务不存在 | 404 |
| `CONCURRENT_MODIFICATION` | 并发领取/乐观修改冲突：任务已被其他执行者领取或状态已变更 | 409 |
| `LEASE_CONFLICT` | 租约令牌无效、已被他人接管，或非持有者操作 | 409 |
| `LEASE_EXPIRED` | 租约已过期，任务已/将被回收 | 409 |
| `DUPLICATE_SUBMISSION` | 相同结果已被接受的重复提交 | 409 |
| `ILLEGAL_TRANSITION` | 非法状态流转（如终态任务领取、退避期内领取） | 409 |
| `TERMINAL_STATE` | 任务已终态，拒绝续租/提交/取消 | 409 |
| `CURSOR_INVALID` | 游标被篡改或格式非法 | 400 |
| `CURSOR_EXPIRED` | 游标超过有效期 | 400 |
| `CURSOR_ANCHOR_DELETED` | 游标锚点记录已删除 | 400 |

---

## 6. HTTP 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/tasks` | 创建任务，body `{"payload":"..."}` |
| GET | `/api/tasks?cursor=&limit=` | 游标分页（limit 默认 20，上限 200） |
| GET | `/api/tasks/{id}` | 任务详情 |
| POST | `/api/tasks/claim` | 领取指定任务 `{workerId, taskId}` |
| POST | `/api/tasks/claim-any` | 领取任意可派发任务 `{workerId}`，无任务返回 204 |
| POST | `/api/tasks/{id}/renew` | 续租 `{workerId, leaseToken}` |
| POST | `/api/tasks/{id}/complete` | 提交结果 `{workerId, leaseToken, succeeded, errorMessage}` |
| POST | `/api/tasks/{id}/cancel` | 取消 |
| DELETE | `/api/tasks/{id}` | 删除（管理/演示；可用于触发锚点删除场景） |

任务对象字段：`id, payload, status, workerId, leaseToken, leaseExpiresAt, timeoutCount,
attemptCount, notBefore, lastError, createdAt, updatedAt`。

---

## 7. 配置项（`application.properties`，前缀 `durable`）

| 键 | 默认值 | 说明 |
|---|---|---|
| `lease-ttl` | 30s | 领取时租约时长 |
| `renew-ttl` | 30s | 续租时长 |
| `max-attempts` | 3 | 最大尝试次数（含首次），用尽则 FAILED |
| `backoff-base` | 2s | 首次失败退避基数 |
| `backoff-multiplier` | 2.0 | 退避倍数（指数） |
| `backoff-max` | 60s | 退避上限 |
| `reaper-interval` | 5s | 租约回收扫描周期（测试中设 1h，靠手动调用） |
| `cursor-ttl` | 10m | 游标有效期 |
| `cursor-secret` | 固定演示值 | 游标 HMAC 密钥 |
| `store-file` | `data/task-store.json` | 持久化快照路径 |

---

## 8. 持久化与并发实现说明

- `FileTaskStore`：内存 `ConcurrentHashMap` 承载并发；所有状态变更走 `mutate(id, mutation)`，
  在 `compute` 的键级锁内完成“加载快照 → 业务判定 → 写回”，业务规则不通过直接抛异常、不落盘，
  天然实现乐观互斥，无需版本号字段也不会丢失更新。
- 每次变更后把全量数据序列化为 JSON，写入同目录临时文件并 `ATOMIC_MOVE` 覆盖快照
  （不支持原子 move 的文件系统退化为普通 replace），保证快照不会写坏；启动时自动加载恢复。
- 时钟统一注入 `java.time.Clock`：生产用系统 UTC 时钟，测试用 `MutableClock` 手动推进，
  从而对租约过期、退避窗口、游标过期做确定性测试。

## 9. 测试覆盖与日志

测试位于 `src/test/java/.../tests`，每个测试的日志都打印关键输入（taskId、worker、token、时钟推进量）
与判定依据（拿到的 errorCode 及其原因），便于排查：

| 测试类 | 覆盖场景 |
|---|---|
| `ConcurrentClaimTest` | 16 个执行者并发领取：恰好 1 成功，其余全部 CONCURRENT_MODIFICATION |
| `DuplicateSubmissionTest` | 重复提交=DUPLICATE_SUBMISSION；终态不同结果=TERMINAL_STATE；过期=LEASE_EXPIRED；被接管/伪造令牌=LEASE_CONFLICT |
| `LeaseReaperTest` | 超时回收、退避窗口内拒绝领取、2s/4s/8s 退避递增、3 次用尽置 FAILED、续租后不误回收 |
| `IllegalTransitionTest` | PENDING 直接提交/续租、RUNNING 取消、终态取消、取消后不重派、不存在任务 |
| `CursorPaginationTest` | 稳定翻页不重不漏；篡改/格式伪造=CURSOR_INVALID；过期=CURSOR_EXPIRED；锚点删除=CURSOR_ANCHOR_DELETED；翻页中增删 |
| `ApiIntegrationTest` | 真实 HTTP 端口端到端，断言状态码与 errorCode |
| `PersistenceRecoveryTest` | 写入快照后重建存储（模拟重启），状态/令牌/次数完整恢复 |

```bash
mvn test                                  # 全部测试
mvn -Dtest=LeaseReaperTest test           # 单类
cat target/surefire-reports/*.txt         # 结果汇总；详细判定依据见控制台日志
```
