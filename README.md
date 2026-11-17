# comagic-mobile

Comagic CLI 的移动客户端：连接开发机上的 `comagic web` 服务，在手机上发消息、看流式输出、审批工具调用。

## 目录

```
comagic-mobile/
├── android/        Android 客户端（Kotlin + Compose，Gradle）
├── ios/            iOS 客户端（SwiftUI，XcodeGen）
├── scripts/        开发机侧配对工具（二维码生成）
└── docs/           图标等共享素材
```

## 配对

```bash
# 一个终端：起服务 + 打印二维码（自动抓密码和内网地址）
./scripts/pair.sh /Users/txc

# 或者服务已经在跑
./scripts/pair-qr.sh http://192.168.1.10:4907 <终端里的密码> /Users/txc
```

App 里点「扫码配对」扫这个二维码即可；也可以手填地址、用户名 `comagic`、密码。

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
| 鉴权 | `Authorization: Basic base64(comagic:<启动时随机密码>)` |
| 会话列表 | `GET /api/session?limit=50&order=desc` |
| 新建会话 | `POST /api/session` body `{agent, model{id,providerID,variant}, location{directory}}` |
| 历史消息 | `GET /session/{id}/message` |
| 发消息 | `POST /session/{id}/message` body `{parts:[{type:"text",text}], agent, model{providerID,modelID}}` |
| 流式事件 | `GET /event`（SSE，`{id,type,properties}`） |
| 中断 | `POST /session/{id}/abort` |

注意：`/api/session/{id}/prompt` 只入队不执行，客户端不要用它发消息。

## 安全

- 密码存在 Android EncryptedSharedPreferences / iOS Keychain。
- `comagic web --hostname 0.0.0.0` 等于把可执行 shell 的 agent 暴露到局域网，只在可信网络使用，用完改回 `127.0.0.1`。
- 二维码里含密码，别截图外发；密码每次重启服务都会变。
