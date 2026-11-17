# comagic-mobile

Comagic CLI 的移动客户端：连接开发机上的服务，在手机上发消息、看流式输出、审批工具调用。开发机侧可以直接用 `comagic web`，也可以用本仓库自带、不依赖 Web 的 [bridge 服务](server/README.md)。

## 目录

```
comagic-mobile/
├── android/        Android 客户端（Kotlin + Compose，Gradle）
├── ios/            iOS 客户端（SwiftUI，XcodeGen）
├── server/         自建 bridge 服务（Python，基于 comagic run --format json）
├── scripts/        开发机侧配对工具（二维码生成）
└── docs/           图标等共享素材
```

## 开机自启（macOS）

用 `scripts/serve.sh` + LaunchAgent 让 bridge 服务随登录自动启动（固定密码，不会每次重配）：

```bash
# 注册 LaunchAgent（已注册可跳过）
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.comagic.mobile-server.plist

tail -f ~/Library/Logs/comagic-mobile/server.log
```

- 固定账号密码：`comagic` / `YFl4cVaQ8smozi4I`（App 新增服务器时已预填），可用 `COMAGIC_MOBILE_USERNAME` / `COMAGIC_MOBILE_PASSWORD` 覆盖。
- 服务端口 `4907`，监听 `0.0.0.0`，默认目录 `$HOME`，可用 `COMAGIC_MOBILE_PORT` / `COMAGIC_MOBILE_HOST` / `COMAGIC_MOBILE_DIR` 覆盖。
- 崩溃会自动重启（`KeepAlive`）；停止：`launchctl bootout gui/$(id -u)/com.comagic.mobile-server`。
- 这是登录后启动；如需开机未登录也启动，需要把 plist 放到 `/Library/LaunchDaemons` 并用 root 加载。

## 配对

### 方式一：自建 bridge 服务（推荐，不依赖 comagic web）

```bash
python3 server/comagic_server.py --directory /Users/txc --port 4907
```

首次启动会打印地址、用户名 `comagic` 和密码，并生成配对二维码。密码默认固定为 `YFl4cVaQ8smozi4I`（可用 `--password` 或 `COMAGIC_MOBILE_PASSWORD` 覆盖）。详见 [server/README.md](server/README.md)。

### 方式二：直接用 comagic web

```bash
# 一个终端：起服务 + 打印二维码（自动抓密码和内网地址）
./scripts/pair.sh /Users/txc

# 或者服务已经在跑
./scripts/pair-qr.sh http://192.168.1.10:4907 <终端里的密码> /Users/txc
```

App 里点「扫码配对」扫这个二维码即可；也可以手填地址、用户名 `comagic`、密码。

新建会话时会先进入目录浏览，选择目标机器上的工作目录，再创建会话。

会话列表右上角的「服务器」可以管理已配对的机器：查看列表、切换当前服务器、添加、编辑或删除。密码保存在系统加密存储（Android EncryptedSharedPreferences / iOS Keychain）里。

`comagic web` 默认会自动打开浏览器，`pair.sh` 用一个临时的 `open` 占位脚本把它屏蔽掉了。如果想自己起服务也不弹浏览器：

```bash
D=$(mktemp -d) && printf '#!/bin/sh\nexit 0\n' > "$D/open" && chmod +x "$D/open"
PATH="$D:$PATH" comagic web --hostname 0.0.0.0 --port 4907
```

## Android

```bash
cd android
./build.sh              # 编 debug
./build.sh test         # JVM 单测
./build.sh install --launch
./build.sh run          # 装好并 adb reverse 4907 后启动
```

要求：JDK 17+、Android SDK（`local.properties` 会自动生成）。

## iOS

```bash
cd ios
xcodegen generate       # 改过 project.yml 后重新生成
open ComagicMobile.xcodeproj
# 或命令行
xcodebuild -scheme ComagicMobile -destination 'platform=iOS Simulator,name=iPhone 18 Pro' build
```

要求：Xcode 15+、[XcodeGen](https://github.com/yonaskolb/XcodeGen)（`brew install xcodegen`）。

iOS 单测会读 `/tmp/comagic_test_env.json`（`{"url":..., "pass":...}`）连真实服务，文件不存在时跳过。

## 服务端协议要点

| 用途 | 接口 |
| --- | --- |
| 鉴权 | `Authorization: Basic base64(comagic:YFl4cVaQ8smozi4I)` |
| 会话列表 | `GET /api/session?limit=50&order=desc` |
| 新建会话 | `POST /api/session` body `{agent, model{id,providerID,variant}, location{directory}}` |
| 删除会话 | `DELETE /api/session/{id}` |
| 模式列表 | `GET /api/agent?location[directory]=<dir>`（取 `mode=primary` 且非 hidden） |
| 模型列表 | `GET /config/providers?directory=<dir>`（聚合 `providers[].models`） |
| 切换模式 | `POST /api/session/{id}/agent` body `{agent}` |
| 切换模型 | `POST /api/session/{id}/model` body `{model{id,providerID,variant?}}` |
| 历史消息 | `GET /session/{id}/message` |
| 发消息 | `POST /session/{id}/message` body `{parts:[{type:"text",text}] 或 parts:[{type:"file",mime,filename,url:"data:..."},{type:"text",text}], agent, model{providerID,modelID}}` |
| 流式事件 | `GET /event`（SSE，`{id,type,properties}`） |
| 中断 | `POST /session/{id}/abort` |

注意：`/api/session/{id}/prompt` 只入队不执行，客户端不要用它发消息。

聊天输入框下方可以像 Web 端一样选择模式和模型，切换会同步到当前会话（`/api/session/{id}/agent|model`）。

图片会先压到最长边 1600px 的 JPEG，再以 data URL 放进 `file` part 发送。语音输入走系统语音识别（Android `SpeechRecognizer` / iOS `SFSpeechRecognizer`），识别结果填进输入框后按文本发送，不上传音频。

## 安全

- 密码存在 Android EncryptedSharedPreferences / iOS Keychain。
- `comagic web --hostname 0.0.0.0` 等于把可执行 shell 的 agent 暴露到局域网，只在可信网络使用，用完改回 `127.0.0.1`。
- 二维码里含密码，别截图外发；密码每次重启服务都会变。
