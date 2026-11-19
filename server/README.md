# Comagic Mobile Bridge Server

一个只依赖 Python 3 标准库的本地服务：把手机 App 的 HTTP/SSE 请求转成 `comagic run --format json` 子进程，不再依赖 `comagic web`。

```
Android / iOS  ──HTTP+SSE──▶  agents/comagic.py  ──spawn──▶  comagic run --format json
                                      │
                                      └── state.json（手机侧会话 ID ↔ comagic 会话 ID）
```

## 注册中心（多台开发机）

`server/server.py` 是一个注册中心 + 反向代理：bridge 启动时向它注册，App 只连它，通过 `/s/<server_id>/...` 转发到对应机器。

```
bridge（4907）──启动注册/心跳──▶  注册中心（4908）
App ──HTTP+SSE──▶ 注册中心 /s/<id>/... ──转发──▶ bridge
```

```bash
# 1) 起注册中心
python3 server/server.py --port 4908

# 2) bridge 注册到它
python3 agents/comagic.py --port 4907 --registry http://<本机IP>:4908

# 3) 查看已注册的服务器
curl -u comagic:YFl4cVaQ8smozi4I http://<本机IP>:4908/api/agents

# 4) App 里把服务器地址填成代理地址，账号密码用注册中心的
#    http://<本机IP>:4908/s/<server_id>
```

注册中心接口（均需 Basic Auth，默认 `comagic` / `YFl4cVaQ8smozi4I`）：

| 接口 | 说明 |
| --- | --- |
| `POST /api/register` | bridge 注册 `{name,type,url,username,password,directory,version,defaultAgent,defaultModel}`，`type` 为 `comagic` 或 `cursor`；`defaultAgent`/`defaultModel` 为该 agent 的默认会话类型与默认模型（`{id,providerID}`） |
| `POST /api/agents/{id}/heartbeat` | 心跳，超过 60s 标记 offline |
| `DELETE /api/agents/{id}` | 注销（bridge 退出时自动调用） |
| `GET /api/agents` | 已注册服务器列表（含 online 状态、`defaultAgent`、`defaultModel`） |
| `GET /global/health` | 健康检查 |

bridge 相关参数：`--registry`（`COMAGIC_REGISTRY`）、`--registry-username`、`--registry-password`、`--name`。

> 注册中心会保存各 bridge 的 Basic Auth 凭据用于代理，只应在可信局域网运行。

## 启动

```bash
python3 agents/comagic.py --directory /Users/txc --port 4907
```

首次启动会打印地址、用户名 `comagic` 和密码，并调用 `scripts/pair-qr.sh` 生成配对二维码。手机 App 扫码或手填即可。密码默认固定为 `YFl4cVaQ8smozi4I`。

二维码渲染按顺序尝试 `swift`（macOS）、`python3 -c "import qrcode"`、`qrencode`；Linux 上若都没有，先 `pip3 install qrcode` 即可。

常用参数 / 环境变量：

| 参数 | 环境变量 | 默认 |
| --- | --- | --- |
| `--host` | `COMAGIC_MOBILE_HOST` | `0.0.0.0` |
| `--port` | `COMAGIC_MOBILE_PORT` | `4907` |
| `--password` | `COMAGIC_MOBILE_PASSWORD` | `YFl4cVaQ8smozi4I` |
| `--directory` | `COMAGIC_MOBILE_DIR` | 当前目录 |
| `--no-qr` | – | 不打印二维码 |
| – | `COMAGIC_BIN` | `comagic` 或 `~/.comagic/bin/comagic` |
| – | `COMAGIC_MOBILE_STATE` | `~/.comagic-mobile/state.json` |
| – | `COMAGIC_RUN_AUTO=1` | 给每次 `comagic run` 加 `--auto`（自动批准权限，谨慎） |

## 工作方式

- 手机新建会话时只返回一个占位 ID（`ses_mobile_...`），不会立刻启动模型。
- 第一次发消息时才启动 `comagic run --format json`，从输出事件里拿到真实 `ses_...` 并写入 `state.json` 映射。
- 后续消息用 `-s <真实会话ID>` 继续同一会话。
- 助手文本、工具调用、错误、空闲等事件经 SSE `/event` 转发；手机端按 `sessionID` 过滤。
- 历史消息用 `comagic export <会话ID>` 读取；会话列表用 `comagic session list --format json`。
- 模式和模型每次启动时通过 `--agent` / `--model`（及 `--variant`）传入；切换只记录在服务端，对下一轮生效。
- 图片会解码到临时文件，用 `comagic run -f <file>` 作为附件发送，运行结束后删除。
- 语音仍然由手机端系统语音识别转文字后按文本发送。

> 注意：`comagic export` 在 stdout 是管道且内容较大（内联图片）时会被截断到约 64KB，本服务把 stdout 重定向到临时文件规避了这个问题。

## 兼容的接口

| 用途 | 接口 |
| --- | --- |
| 鉴权 | `Authorization: Basic base64(comagic:<密码>)` |
| 健康检查 | `GET /global/health` |
| 会话列表 | `GET /api/session?limit=50&order=desc` |
| 新建会话 | `POST /api/session` body `{agent, model, location{directory}}` |
| 历史消息 | `GET /session/{id}/message` |
| 发消息 / 图片 | `POST /session/{id}/message` body `{parts:[...]} ` |
| 流式事件 | `GET /event`（SSE） |
| 中断 | `POST /api/session/{id}/interrupt` 或 `POST /session/{id}/abort` |
| 删除会话 | `DELETE /api/session/{id}` |
| 路径信息 | `GET /path?directory=<dir>` |
| 目录浏览 | `GET /api/fs/list?location[directory]=<base>&path=<dir>` |
| 模式列表 | `GET /api/agent` |
| 模型列表 | `GET /config/providers` |
| 切换模式 | `POST /api/session/{id}/agent` body `{agent}` |
| 切换模型 | `POST /api/session/{id}/model` body `{model{id,providerID,variant?}}` |

## 限制

- 每条消息启动一个 CLI 子进程，冷启动比常驻的 `comagic web` 慢；同一会话同时只允许一个运行中的请求。
- `comagic run` 没有可交互的权限确认通道：权限由 comagic 的配置决定；需要自动批准时自行开启 `COMAGIC_RUN_AUTO=1`。
- 模式列表是内置的 `build` / `plan`；模型列表通过 `comagic models --verbose` 解析。
- 会话标题、agent/model 在首次运行结束后从 `comagic export` 回填。
- 没有实现附件以外的消息编辑、回滚、fork 等 Web 端功能。

## 安全

- 服务使用 Basic Auth，密码默认固定为 `YFl4cVaQ8smozi4I`；只建议在可信局域网使用，必要时用 `--password` 覆盖。
- 该服务等价于把可执行命令的 coding agent 暴露给能访问端口的人，不要直接映射到公网。
- 新建会话的目录浏览接口可以读取目标机器的目录结构，只对可信设备开放。
- `--auto` 会自动批准权限请求，也只应在可信环境使用。
