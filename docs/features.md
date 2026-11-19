# Comagic Mobile 功能说明

移动端（Android / iOS）连接开发机上的 **agent**，在手机上发起会话、查看流式输出并审批命令。多台机器通过**注册中心**统一发现，App 只需登录注册中心即可自动获取 agents。

---

## 1. 整体架构

```
Android / iOS App
        │  登录（注册中心 IP / 账号 / 密码）
        ▼
   注册中心 server/server.py (4908)
        │  /api/agents 发现
        │  /s/<agent_id>/... 反向代理（含 SSE）
        ▼
   Agent agents/comagic_agent.py (4907) 或 agents/cursor_agent.py (4909)
        │
        ▼
   comagic run --format json  /  agent -p --output-format stream-json
```

- **注册中心**：保存各 agent 的名称、类型、版本、在线状态，并做 HTTP/SSE 反向代理。
- **Agent**：把 App 的请求转换成 CLI 子进程调用，管理会话状态；`type` 为 `comagic` 或 `cursor`。
- **App**：只与注册中心通信，不直连各台开发机。

---

## 2. 快速开始

### 2.1 启动注册中心

```bash
python3 server/server.py --port 4908
```

默认账号密码：`comagic` / `YFl4cVaQ8smozi4I`。

### 2.2 在开发机启动 agent 并注册

```bash
# Comagic
python3 agents/comagic_agent.py \
  --port 4907 \
  --directory ~ \
  --registry http://<注册中心IP>:4908 \
  --no-qr

# Cursor（需 PATH 上有 agent，并已登录）
python3 agents/cursor_agent.py \
  --port 4909 \
  --directory ~ \
  --registry http://<注册中心IP>:4908 \
  --no-qr
# 或: ./scripts/serve-cursor.sh
```

注册后可查看在线 agents：

```bash
curl -s -u comagic:YFl4cVaQ8smozi4I http://<注册中心IP>:4908/api/agents
```

### 2.3 登录 App

首次打开显示登录页，填写：

- 注册中心 IP（只填 IP 即可，自动补 `http://` 和 `:4908`）
- 账号 / 密码

登录成功后进入 **agents** 主页，自动发现并添加可用的 agent。

### 2.4 supervisor（可选，常驻）

```ini
[program:agent-registry]
command=/usr/bin/python3 -u /path/to/comagic-mobile/server/server.py --host 0.0.0.0 --port 4908

[program:comagic-agent]
command=/path/to/comagic-mobile/scripts/serve.sh
environment=HOME="/home/user",COMAGIC_REGISTRY="http://192.168.100.136:4908"

[program:cursor-agent]
command=/path/to/comagic-mobile/scripts/serve-cursor.sh
environment=HOME="/home/user",COMAGIC_REGISTRY="http://192.168.100.136:4908",PATH="/home/user/.local/bin:%(ENV_PATH)s"
```

---

## 3. App 功能

### 3.1 登录与注册中心

- 启动先登录注册中心，凭据加密保存（Android EncryptedSharedPreferences、iOS Keychain）。
- 登录页的注册中心 IP 支持下拉列表：可添加、删除、切换多个 IP。
- 登录后根页面为 **agents**。

### 3.2 agents 主页

- 展示已发现的 agents：名称、agent id、类型、版本、在线状态。
- 右上角：
  - **刷新**：重新请求注册中心并探测各 agent 健康状态。
  - **设置**：进入设置页。
  - **退出**：清除登录信息，回到登录页。
- 左滑 agent 显示「取消 / 删除」。
  - 删除只影响本机展示，并写入隐藏列表，刷新后不会再次出现。
  - 设置页提供「恢复已删除的 agents」。
- 只显示注册中心返回的**在线** agents，且类型为 `comagic` / `cursor`（或未标注类型）。

### 3.3 设置页

- 注册中心 IP、当前账号。
- 会话配置：**显示隐藏目录**（新建会话选择工作目录时是否显示以 `.` 开头的目录）。
- 恢复已删除的 agents。
- 退出登录。

### 3.4 会话与聊天

- 选择 agent 后进入其会话列表：搜索、刷新、新建会话、删除会话（左滑）。
- 新建会话：浏览目标机器目录并选择工作目录。
- 聊天页支持：
  - 流式输出与工具调用展示
  - 命令审批（允许一次 / 拒绝）
  - 中断执行
  - 切换 agent 模式（build / plan 等）
  - 切换模型
  - 发送图片
  - 语音输入
  - 历史消息分页加载
  - 断线自动重连
- 返回：聊天页 → 会话列表 → agents。

---

## 4. 注册中心 API

均需 Basic Auth。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/register` | agent 注册：`{name,type,url,username,password,directory,version}` |
| GET | `/api/agents` | 在线 agents 列表（离线不返回） |
| POST | `/api/agents/{id}/heartbeat` | 心跳，超过 60s 视为离线 |
| DELETE | `/api/agents/{id}` | 注销（agent 退出时自动调用） |
| GET | `/global/health` | 注册中心健康检查 |
| ANY | `/s/{id}/...` | 反向代理到对应 agent（支持 SSE） |

同 URL 重复注册会复用原 id。

---

## 5. Agent API（App 经注册中心访问）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/global/health` | 返回 `{healthy, version}`，version 为 git 提交数 |
| GET | `/event` | SSE 事件流 |
| GET | `/api/session` | 会话列表 |
| POST | `/api/session` | 新建会话（目录、agent、模型） |
| GET | `/session/{id}/message` | 历史消息 |
| POST | `/session/{id}/message` | 发送消息 |
| POST | `/session/{id}/interrupt` | 中断 |
| POST | `/session/{id}/permission/{req}/reply` | 审批结果 |
| POST | `/api/session/{id}/agent` | 切换 agent 模式 |
| POST | `/api/session/{id}/model` | 切换模型 |
| GET | `/api/agent` | 可用 agent 模式 |
| GET | `/config/providers` | 可用模型 |
| GET | `/path`、`/api/fs/list` | 目录信息与浏览 |

---

## 6. 配置项

### Agent（`agents/comagic_agent.py` / `agents/cursor_agent.py`）

| 参数 | 环境变量（comagic / cursor） | 默认 |
| --- | --- | --- |
| `--host` | `COMAGIC_MOBILE_HOST` / `CURSOR_MOBILE_HOST` | `0.0.0.0` |
| `--port` | `COMAGIC_MOBILE_PORT` / `CURSOR_MOBILE_PORT` | `4907` / `4909` |
| `--username` | `COMAGIC_MOBILE_USERNAME` / `CURSOR_MOBILE_USERNAME` | `comagic` |
| `--password` | `COMAGIC_MOBILE_PASSWORD` / `CURSOR_MOBILE_PASSWORD` | `YFl4cVaQ8smozi4I` |
| `--directory` | `COMAGIC_MOBILE_DIR` / `CURSOR_MOBILE_DIR` | 当前目录 |
| `--name` | `COMAGIC_MOBILE_NAME` / `CURSOR_MOBILE_NAME` | 主机名 / `cursor@主机名` |
| `--registry` | `COMAGIC_REGISTRY`（两者共用） | 无（不注册） |
| `--registry-username` | `COMAGIC_REGISTRY_USERNAME` | `comagic` |
| `--registry-password` | `COMAGIC_REGISTRY_PASSWORD` | 同上 |
| `--no-qr` | – | 不打印配对二维码（常驻运行用） |
| – | `CURSOR_AGENT_BIN` | `agent` / `cursor-agent` |
| – | `CURSOR_RUN_FORCE=0` | 默认自动 `--force`；设为 `0` 关闭 |

### App

- 默认注册中心：`http://192.168.100.136:4908`
- 可选多个注册中心 IP，登录页下拉管理。
- 隐藏/显示隐藏目录、已删除 agents 均保存在本机。

---

## 7. 安全说明

- 注册中心和 agent 都使用 Basic Auth，默认密码公开，**只应在可信局域网使用**。
- 注册中心保存各 agent 的凭据用于代理。
- App 保存的账号密码使用系统加密存储。
- 该服务等价于把可执行命令的 coding agent 暴露给能访问端口的人，不要直接映射到公网。

---

## 8. 常见问题

**刷新后 agents 数量比预期多？**
旧版会保留注册中心重启前的过期 id。当前版本发现时会自动清理失效条目；仍异常可退出登录后重新登录。

**删除 agent 后刷新又出现？**
当前版本会记录隐藏并跳过；在设置页可恢复。

**版本显示为 `bridge-0.1`？**
说明该 agent 还在跑旧代码，重启后版本变为 git 提交数（如 `42`）。

**接口 404？**
注册中心已改为 `/api/agents`（旧版为 `/api/servers`），agent 与 App 需一起更新并重启。
