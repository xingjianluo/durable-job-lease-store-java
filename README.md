# Durable Job Lease Store（可持久化后台任务租约与状态流转）

一个**零外部依赖**的 Spring Boot 实现：任务创建与游标分页查询、带租约的领取/续租/完成、
租约超时自动回收与指数退避重试、完成结果幂等。持久化使用本地 JSON 快照（原子写），
不依赖数据库、消息队列或第三方服务，本地即可完整验证。

- Java 21+ / Spring Boot 3.4.x
- 存储：内存索引 + `data/jobstore/snapshot.json` 原子快照（临时文件 + rename）
- 并发：单机内置监视器锁，所有“读-判定-写”原子完成；版本号 + 租约 token 双重校验

---

## 1. 快速开始

```bash
# 运行全部测试（22 个用例，含并发/租约/游标/持久化/HTTP 端到端）
mvn test

# 启动服务（随机端口，见启动日志 Tomcat started on port ...）
mvn spring-boot:run
```

启动后用 `curl` 走一遍（端口替换为日志中的实际端口）：

```bash
BASE=http://localhost:8080

# 1) 创建任务
curl -s -XPOST $BASE/api/jobs -H 'Content-Type: application/json' \
  -d '{"type":"email","payload":"hello"}'

# 2) 列表（游标分页，稳定顺序）
curl -s "$BASE/api/jobs?limit=20"

# 3) 领取（返回 leaseToken，后续请求用 X-Lease-Token 携带）
curl -s -XPOST "$BASE/api/jobs/claim?workerId=worker-1"

# 4) 提交成功 / 失败
curl -s -XPOST "$BASE/api/jobs/<id>/complete" -H 'X-Lease-Token: <token>' \
  -H 'Content-Type: application/json' -d '{"succeeded":true,"result":"ok"}'

# 5) 续租
curl -s -XPOST "$BASE/api/jobs/<id>/renew" -H 'X-Lease-Token: <token>'

# 6) 取消（仅 WAITING 可取消）/ 删除终态任务
curl -s -XPOST "$BASE/api/jobs/<id>/cancel"
curl -s -XDELETE "$BASE/api/jobs/<id>"
```

删除 `data/jobstore/snapshot.json` 即可重置全部数据。

---

## 2. 状态模型

```
                 ┌──────────────────── 租约超时 / 主动失败（退避后） ───────────────────┐
                 ▼                                                                     │
 ┌─────────┐  claim   ┌─────────┐  complete(success)            ┌───────────┐
 │ WAITING │────────▶│ RUNNING │──────────────────────────────▶│ SUCCEEDED │ 终态
 │ 待领取   │         │ 执行中   │  complete(failure) & 次数用尽  ├───────────┤
 └─────────┘         └─────────┘──────────────────────────────▶│  FAILED   │ 终态
     │  cancel          │  租约超时 & 次数用尽                   └───────────┘
     ▼                  └──────────────────────────────────────▶┌───────────┐
 ┌───────────┐                                                  │ CANCELED  │ 终态
 │ CANCELED  │ 终态                                              └───────────┘
 └───────────┘
```

- **WAITING（待领取）**：新建任务、或失败/超时回收后处于退避窗口结束的任务。
- **RUNNING（执行中）**：已被某 worker 领取并持有有效租约。
- **SUCCEEDED / FAILED / CANCELED**：终态，不再接受任何流转。

合法流转：`WAITING→RUNNING`（claim）、`RUNNING→SUCCEEDED/FAILED/WAITING`（complete）、
`RUNNING→WAITING/FAILED`（租约回收）、`WAITING→CANCELED`。其它一律拒绝，错误码 `ILLEGAL_TRANSITION`。

---

## 3. 租约规则

- 领取成功后返回随机不可猜测的 **leaseToken**，作为持有者身份凭证；响应中同时给出
  `leaseHolder` 与 `leaseExpiresAtMillis`。
- 续租 / 完成必须在请求头 `X-Lease-Token` 携带**当前有效** token。
- 三种拒绝原因明确可区分：

| 场景 | 错误码 | HTTP |
|---|---|---|
| 租约已过期（墙钟超过 expiresAt） | `LEASE_EXPIRED` | 409 |
| token 不匹配（租约已被他人接管 / 伪造 token） | `LEASE_TAKEN_OVER` | 409 |
| 任务不在 RUNNING（终态等）操作 | `ILLEGAL_TRANSITION` | 409 |
| 并发版本冲突（CAS 失败，防止静默丢更新） | `VERSION_CONFLICT` | 409 |
| 并发领取同一任务（后到者） | `LEASE_CONFLICT` / `VERSION_CONFLICT` | 409 |
| 任务不存在 | `NOT_FOUND` | 404 |
| 参数非法 | `VALIDATION_ERROR` | 400 |

### 3.1 超时回收与重新派发

后台 `LeaseReaper` 每 `job.reaper-interval-millis` 扫描一次 RUNNING 且租约到期的任务：

- 已尝试次数 `attempts < job.max-attempts`：回到 WAITING，设置
  `notBefore = now + min(job.base-backoff-millis * 2^(attempts-1), job.max-backoff-millis)`，
  **退避逐步拉长**，未到 notBefore 不会被领取；
- `attempts >= job.max-attempts`：置 FAILED 终态（completion 记录“租约超时且重试次数已达上限”），
  **不会无限重派**。

续租把到期时间整体后移一个 TTL，活跃 worker 不会被回收；任务被接管后，旧持有者的续租与
完成请求都会以 `LEASE_TAKEN_OVER` 拒绝。

### 3.2 完成幂等

完成请求只接受一次。任务已终态且存在 completion 记录时，重复提交（例如 worker 重试）：

- HTTP **200**，响应 `duplicate=true`，并原样返回**首次** completion（成功/失败、result、完成时间）；
- 状态、completion、version 都不会被后续重复请求覆盖，因此“失败的重复提交”无法把成功改写成失败。

---

## 4. 游标语义与并发翻页

- 排序键固定为 **(createdAtMillis, seq) 升序**。`seq` 为全局单调递增序号（跨重启继续递增，
  持久化在快照中），与记录是否被删除无关，保证顺序稳定。
- 游标为不透明串：`base64url(seq.createdAt.issuedAt) + "." + base64url(HMAC-SHA256(payload))`。
  翻页从“锚点记录的下一条”继续，因此：
  - 翻页期间**新增**记录（seq 更大，排在末尾）不会让旧记录重复或漏读；
  - 翻页期间**删除**“锚点之外”的未读记录，只是少返回该条，其余不重不漏。
- 三类游标失败明确可区分：

| 场景 | 错误码 / 字段 | HTTP |
|---|---|---|
| 格式错误、Base64 非法、HMAC 签名不匹配（**被篡改**） | `CURSOR_INVALID` | 400 |
| 签名合法但签发时间超过 `job.cursor-ttl-millis`（**过期**） | `CURSOR_EXPIRED` | 410 |
| 签名合法、未过期，但锚点 seq 指向的记录已删除 | 200 且响应体 `anchorFound=false`、items 为空 | 200 |

> 锚点删除的语义是“这页的起点已经不存在”，调用方应放弃旧游标、重新从第一页开始
> （或由上层决定如何处理缺口），而不是继续使用该游标。

列表响应：`{ "items": [...], "nextCursor": "...或null", "anchorFound": true }`，
`nextCursor=null` 表示已到末页。

---

## 5. 持久化与并发安全

- 每次成功变异后立即把全量快照写入 `snapshot.json.tmp` 再原子 rename 为
  `snapshot.json`；启动时自动加载恢复（任务状态、租约持有者/到期时间、attempts、seq 均恢复）。
- 所有变异方法在存储层同一把锁内完成“读取当前版本 → 条件判定 → 写入新版本号”，
  配合 version 乐观号杜绝并发覆盖与静默丢更新。
- 进程在租约有效期内重启：恢复后租约到期时间按墙钟判定，到期即被回收，不会出现
  “僵尸任务永久占用”。

---

## 6. 配置项（`src/main/resources/application.properties`，前缀 `job`）

| 键 | 默认值 | 说明 |
|---|---|---|
| `job.lease-ttl-millis` | 30000 | 租约时长 |
| `job.reaper-interval-millis` | 1000 | 回收器扫描间隔 |
| `job.max-attempts` | 3 | 最大尝试次数（含首次） |
| `job.base-backoff-millis` | 2000 | 首次重试退避基数 |
| `job.max-backoff-millis` | 60000 | 退避上限 |
| `job.cursor-ttl-millis` | 300000 | 游标有效期 |
| `job.cursor-secret` | （内置开发值） | HMAC 签名密钥 |
| `job.data-dir` | `data/jobstore` | 快照目录，置空为纯内存 |

---

## 7. 测试覆盖

```bash
mvn test
```

| 测试类 | 覆盖场景 |
|---|---|
| `StateTransitionTest` | 合法/非法状态流转、终态拒绝、错误码可区分 |
| `ConcurrencyLeaseTest` | 8 worker 并发领取同一任务仅 1 成功、过期接管、旧 token 拒绝、续租保活、重复完成幂等、伪造 token |
| `LeaseReaperRetryTest` | 租约过期回收、退避窗口内不可领取、退避逐步拉长、重试上限置 FAILED 且永不重派、主动失败走相同退避规则 |
| `CursorPaginationTest` | 稳定排序不重不漏、游标篡改(翻字符/乱码/伪造签名)、过期、锚点删除 anchorFound=false、翻页中增删 |
| `PersistenceRecoveryTest` | 重启后状态/租约/attempts/seq 恢复，过期租约重启后回收，seq 跨重启单调 |
| `ApiIntegrationTest` | 真实 HTTP 端到端：全生命周期、各错误码经网络传递、HTTP 层并发领取 |

测试日志会打印关键输入（任务 id、token、时钟快进量、游标原文）与判定依据
（拒绝 code、退避到的 notBefore、排序 seq），便于排查。测试通过受控时钟
（`ManualTimeSource`）快速模拟租约过期与退避，无需真实等待。
