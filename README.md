# tabfix-chatbridge

**Velocity 代理端插件：TAB 列表假人修复 + 跨服聊天（Minecraft 1.21.1 / Velocity 4.3.0）**

> 本仓库为**个人自用**的原创插件，作者 **Melor**，采用 **MIT** 许可证，可自由使用与修改。

---

## 1. 项目概述

两个独立的 Velocity 插件，配套使用于「Velocity 代理 + 多个 Fabric 子服」的跨服架构：

| 插件 | 版本 | 作用 |
| --- | --- | --- |
| **TabFix** | 1.3.1 | 让子服侧的假人（Carpet `/player` 等）在 TAB 列表中稳定显示；按观察者裁剪真人玩家的服务器前缀 |
| **ChatBridge** | 1.1.0 | 统一的跨服聊天、跨服加入/离开/切换子服提示、跨服 `/msg` |

本仓库所有代码均为原创，**不包含任何第三方项目的源代码**。

---

## 2. 上游项目与版权

本项目为**原创作品**，不存在 fork 或二次开发，因此**没有上游仓库**。

| 组件 | 作者 | 许可证 |
| --- | --- | --- |
| tabfix-chatbridge（本仓库全部代码） | Melor | MIT |

### 运行时依赖（非本仓库代码，仅 API 层面调用）

| 依赖 | 作者 | 许可证 | 说明 |
| --- | --- | --- | --- |
| Velocity Proxy | VelocityPowered | GPL-3.0 | 插件宿主的 API |
| Velocitab | William278 & AlexDev03 | Apache-2.0 | TAB 列表格式化（TabFix 与之协同，非修改） |
| SignedVelocity | 4drian3d | LGPL-3.0 | 聊天签名转发 |
| Minecraft / Fabric | Mojang / FabricMC | 各自版权 | 平台与模组加载器 |

> **重要：** 本仓库**不含**上述任何依赖的代码或二进制制品，仅在编译时需要 Velocity API 的 jar 作为 classpath。详见 [THIRD-PARTY-NOTICES.md](./THIRD-PARTY-NOTICES.md)。

---

## 3. TabFix

### 3.1 解决的问题

**问题现象：** 切换子服或重新进服后，Carpet 假人从 TAB 列表中消失。

**根因（按逆向 Velocity / Velocitab 字节码定位）：**

1. 假人不是「代理玩家」，只能依赖子服下发的 `UpsertPlayerInfoPacket` 出现在 TAB。
2. `Velocitab#updateTabListOnJoin` 在玩家进服时会遍历**客户端真实的 TAB 条目**，把所有 profile UUID 不在「代理玩家集合」中的条目逐个 `TabList.removeEntry()` —— 假人因此被删除。
3. 原版 TabFix（1.2.0）用自己维护的 `shown` 集合记录「已发送过哪些假人」，切服后该集合与客户端真实状态**失同步**：`shown` 认为已显示，但客户端实际是空的，于是 `toAdd` 为空，假人再也不回来。

**修复（1.3.0 起）：**

| 改动 | 说明 |
| --- | --- |
| 以 `TabList#containsEntry(UUID)` 为唯一事实来源 | 不再信任自身记账，周期性对齐时直接比对客户端真实条目 |
| 在子服连接上旁挂 `BackendWatcher` | 只有子服**自己**发出的删除包才视为假人真正下线；Velocitab 主动 `removeEntry` 造的误删一律拦截 |
| `filterRemoval()` 拦截误删 | 进服窗口内 / 子服未确认的删除 → 丢弃该条目 |
| 双重身份判定 `isProxyPlayer(uuid, name)` | 避免进服握手瞬间把真人误缓存为假人（历史 bug：`bots.dat` 里混入了真人） |
| 所有 TAB 写操作跑在 `eventLoop()` | 避免跨线程操作 netty 链路 |
| CONFIG 阶段跳过写入 | `getState() != PLAY` 时不对齐，避免切服期间写 PLAY 包 |

### 3.2 前缀裁剪规则（`strip_prefix`）

真人玩家的服务器前缀按观察者视角裁剪，回归接近原版的观感：

| 值 | 行为 |
| --- | --- |
| `same_server`（默认） | 同子服 / 自己 → 原版白名；仅**别的子服**的玩家显示 `【生存】` 之类前缀 |
| `self` | 仅自己看不到前缀 |
| `none` | 全部保留 Velocitab 给的前缀 |

**旁观者（`gameMode == 3`）始终渲染为灰色**，不会被前缀裁剪逻辑刷成白色，保证其他玩家能看出谁是旁观者。

### 3.3 配置项

配置文件：`plugins/tabfix/config.properties`

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `replay` | `true` | 是否补发假人条目 |
| `cross_server` | `false` | 是否跨子服显示假人（Carpet 假人 UUID 由名字决定，同名会冲突，默认关闭） |
| `reconcile_interval_ms` | `1000` | 周期对齐间隔 |
| `replay_delay_ms` | `1500` | 进/切服后首次对齐延迟（需晚于 Velocitab 的进服清理） |
| `cache_ttl_seconds` | `0` | 假人缓存有效期，`0` = 永不过期，靠进服快照复核剔除 |
| `suppress_removal_ms` | `6000` | 进服后多久内拦截所有假人删除包 |
| `snapshot_seconds` | `3` | 进服快照窗口 |
| `ping_interval_seconds` | `0` | 用后端 status ping 校准缓存（服务端样本被匿名化时无效） |
| `strip_prefix` | `same_server` | 见 3.2 |
| `format.bot_name` / `bot_prefix` / `bot_local` | `%name%` / 空 / 空 | 假人显示名模板 |
| `debug` | `false` | 详细日志 |

---

## 4. ChatBridge

### 4.1 功能

- **统一跨服聊天**：`takeover=true` 时由代理接管聊天，带 `【子服名】` 前缀广播到全服。
- **跨服事件提示**：加入、离开、**切换子服**。
- **跨服 `/msg`**：`/msg`、`/tell`、`/whisper`、`/w`。
- **`/chathub`**（别名 `/cb`）：`list` / `msg` / `reload`。
- **命令透传**：以 `bypass_prefixes` 配置的前缀开头（默认 `!!,!s,==,@,在哪`）的消息原样放行给子服，供 MCDR 解析。

### 4.2 切服提示

格式取自 `format.switch`，默认：

```properties
format.switch=【%server_from%】%name% → 【%server_to%】%name%
```

渲染效果：

```
【生存】Sunny_Melor → 【镜像】Sunny_Melor
```

**两点实现细节：**

1. 切服提示广播给**所有人（含切服者本人）**。原实现把「源服、目标服、本人」全部排除，导致实际无人能看到。
2. 箭头必须用 **U+2192 `→`**，不能使用 `➡️`（`U+27A1` + 变体选择符 `U+FE0F`）——默认字体没有 `U+FE0F` 的字形，客户端会渲染成方块。

### 4.3 配置项

配置文件：`plugins/chatbridge/config.properties`（未提供时会自动生成默认值）

| 键 | 说明 |
| --- | --- |
| `takeover` | 是否由代理接管聊天 |
| `bypass_prefixes` | 透传给子服的消息前缀（逗号分隔） |
| `format.chat` / `join` / `leave` / `switch` | 各事件格式模板 |
| `format.list_header` / `list_entry` | `/chathub list` 输出 |
| `format.msg_sender` / `msg_target` / `msg_offline` | 私聊相关 |
| `format.no_permission` | 无权限提示 |
| `color.server.*` / `server.*` | 子服颜色与显示名 |

占位符：`%server%`、`%server_from%`、`%server_to%`、`%name%`、`%message%`、`%target%`、`%sender%`、`%count%`。

---

## 5. 仓库结构

```
tabfix-chatbridge/
├── tabfix/                       # TabFix 插件
│   ├── src/com/melor/tabfix/
│   │   └── TabFix.java
│   ├── velocity-plugin.json
│   └── build.ps1
├── chatbridge/                   # ChatBridge 插件
│   ├── src/com/melor/chatbridge/
│   │   └── ChatBridge.java
│   ├── velocity-plugin.json
│   └── build.ps1
├── docs/
│   └── release-notes.md
├── .gitignore
├── LICENSE                       # MIT
├── THIRD-PARTY-NOTICES.md
└── README.md
```

---

## 6. 构建

需要 **JDK 21+**（`javac --release 21`），以及 Velocity 的 jar 作为编译期 classpath。

```powershell
cd tabfix
.\build.ps1
```

`build.ps1` 会自动定位 JDK、编译 `src` 下全部源码、打包为 `plugins/TabFix-<version>.jar`。

> 脚本中的 `velocityJar` 路径默认为 `../../velocity-4.3.0-44.jar`，请按你的目录结构或 Velocity 版本调整。

**注意：** Velocity 运行时会在内存中映射插件 jar 文件，**覆盖前必须先停止代理**，否则会因文件被占用而失败。

---

## 7. 安装

1. 将 `TabFix-1.3.1.jar`、`ChatBridge-1.1.0.jar` 放入 Velocity 的 `plugins/` 目录；
2. 启动代理，首次运行会在 `plugins/tabfix/`、`plugins/chatbridge/` 下生成默认配置；
3. 按需修改配置后重启（ChatBridge 支持 `/chathub reload` 热重载）。

**环境：**

- Velocity **4.3.0**（需要 Java 25 运行时）
- Minecraft **1.21.1**
- 建议搭配 Velocitab；TabFix 的 `strip_prefix` 逻辑依赖 Velocitab 提供 `【子服】` 格式

---

## 8. 已验证场景

在 Velocity 4.3.0 + 三个 Fabric 子服（survival / creative / mirror）环境实测：

| 场景 | 结果 |
| --- | --- |
| 首次进服，TAB 显示本服假人 | ✅ |
| 切换子服后假人保留 | ✅ |
| 退出重进后假人保留 | ✅ |
| 假人真正下线后被正确移除 | ✅ |
| 真人玩家不再被误缓存为假人 | ✅ |
| 同服玩家显示原版白名 | ✅ |
| 跨子服玩家显示 `【子服】` 前缀 | ✅ |
| 旁观者 ID 显示为灰色（他人视角） | ✅ |
| 切服公屏提示 `【生存】X → 【镜像】X` | ✅ |

---

## 9. 许可证

本项目采用 **MIT License**，版权归 **Melor** 所有。详见 [LICENSE](./LICENSE)。

你可以自由使用、修改、分发本软件，包括商业用途，只需保留原始版权声明。

第三方依赖的许可信息见 [THIRD-PARTY-NOTICES.md](./THIRD-PARTY-NOTICES.md)。
