# crossserver

**Velocity 代理端跨服核心插件：统一聊天 + TAB 列表处理（Minecraft 1.21.1 / Velocity 4.3.0）**

> 个人自用的原创插件，作者 **Melor**，采用 **MIT** 许可证。

---

## 1. 项目概述

一个 Velocity 插件，解决「代理 + 多子服」架构下的两类问题：

| 模块 | 负责 | 面向对象 |
| --- | --- | --- |
| **聊天（Chat）** | 带子服标签的统一聊天、加入/离开/切服提示、跨服 `/msg` | `PlayerChatEvent` 等事件 |
| **TAB（Tab）** | 假人在 TAB 中稳定显示、按观察者裁剪子服前缀、保留子服模组名字颜色 | 注入 netty 管线，改写玩家信息包 |

两者虽然同属一个插件，但**内部保持隔离**：TAB 逻辑封装在独立的 `TabModule` 类中。
原因是 TAB 需要在 netty 的 `write()` 里工作，一旦抛异常会顶掉整条连接；而聊天只订阅事件，出错最多影响单条消息。

本仓库所有代码均为原创，**不包含任何第三方项目的源代码**。

---

## 2. 上游项目与版权

本项目为**原创作品**，不存在 fork 或二次开发，**没有上游仓库**。

| 组件 | 作者 | 许可证 |
| --- | --- | --- |
| crossserver（本仓库全部代码） | Melor | MIT |

### 运行时依赖（非本仓库代码）

| 依赖 | 作者 | 许可证 | 说明 |
| --- | --- | --- | --- |
| Velocity Proxy | VelocityPowered | GPL-3.0 | 插件宿主 API |
| Velocitab | William278 & AlexDev03 | Apache-2.0 | TAB 格式化（协同运行，未修改） |
| SignedVelocity | 4drian3d | LGPL-3.0 | 聊天签名转发 |
| Minecraft / Fabric | Mojang / FabricMC | 各自版权 | 平台 |

> 本仓库**不含**上述依赖的代码或二进制制品。详见 [THIRD-PARTY-NOTICES.md](./THIRD-PARTY-NOTICES.md)。

---

## 3. TAB 模块

### 3.1 解决的核心问题：假人消失

**现象：** 切换子服或重新进服后，Carpet 假人从 TAB 列表消失。

**根因**（逆向 Velocity / Velocitab 字节码定位）：

1. 假人不是「代理玩家」，只能靠子服下发的 `UpsertPlayerInfoPacket` 出现在 TAB。
2. `Velocitab#updateTabListOnJoin` 在玩家进服时遍历**客户端真实的 TAB 条目**，把所有 profile UUID 不在「代理玩家集合」中的条目逐个 `TabList.removeEntry()` —— 假人因此被删除。
3. 早期实现用自己维护的「已发送」集合记录状态，切服后与客户端真实状态**失同步**，导致假人再也补不回来。

**修复要点：**

| 改动 | 说明 |
| --- | --- |
| 以 `TabList#containsEntry(UUID)` 为唯一事实来源 | 不再信任自身记账，周期对齐时直接比对客户端真实条目 |
| 子服连接上旁挂 `BackendWatcher` | 只有子服**自己**发出的删除包才视为假人真正下线；Velocitab 主动 `removeEntry` 造成的误删一律拦截 |
| 双重身份判定 `isProxyPlayer(uuid, name)` | 避免进服握手瞬间把真人误缓存为假人 |
| 写操作跑在 `eventLoop()` | 避免跨线程操作 netty 链路 |
| CONFIG 阶段跳过写入 | `getState() != PLAY` 时不对齐，避免切服期间写 PLAY 包 |

### 3.2 子服前缀裁剪（`strip_prefix`）

| 值 | 行为 |
| --- | --- |
| `same_server`（默认） | 同子服 / 自己 → 原版白名；仅**别的子服**的玩家显示 `【生存】` 前缀 |
| `self` | 仅自己看不到前缀 |
| `none` | 全部保留 Velocitab 给的前缀 |

**旁观者（`gameMode == 3`）始终渲染为灰色**，保证其他玩家能看出谁在旁观。

### 3.3 保留子服模组的名字颜色

这是本项目最微妙的一处。以 [rankboard](https://modrinth.com/mod/rankboard)（排行榜模组）为例，它会把榜单颜色写进 TAB 条目的 displayName。

实际遇到的冲突链条（由日志实证）：

```
① 子服 rankboard     → 把蓝色写进 displayName            ✅ 到达代理
   {"color":"blue","text":"Sunny_Melor"}
② Velocitab          → format_update_rate=1000，每秒重建    ❌ 颜色被冲掉
   {"extra":[{"color":"green","text":"生存"},"】Sunny_Melor"],"text":"【"}
③ 前缀裁剪           → 若重新生成文本，颜色再次丢失          ⚠️
```

**关键在于 ②**：`%username%` 只是纯用户名，`TabPlayer` 里没有任何读取子服显示名的字段，所以 Velocitab 每次重建都会把颜色冲成默认。

**解决方案：在出站最后一道把颜色补回去。**

```java
// ① 先记下子服给这个名字用的颜色
NamedTextColor observed = findColorFor(entry.getDisplayName().getComponent(), username);
if (observed != null) nameColors.put(id, observed);

// ② 每个包出站前检查，被冲掉就贴回去
NamedTextColor restored = nameColors.get(id);
if (restored != null && !matchesColor(current, username, restored)) {
    entry.setDisplayName(new ComponentHolder(version,
        entry.getGameMode() == SPECTATOR_GAME_MODE
            ? Component.text(username, NamedTextColor.GRAY)   // 旁观者仍灰
            : Component.text(username, restored)));            // 其余补回原色
}
```

因为拦的是**最后一环**，无论 Velocitab 覆盖多少次都能改回来，且 `matchesColor()` 保证颜色已正确时不重复改包。

聊天模块读取同一份 `nameColors`，因此 **TAB 里什么颜色，聊天栏就是什么颜色**。

### 3.4 TAB 配置

`plugins/crossserver/tab/tab.properties`

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `replay` | `true` | 是否补发假人条目 |
| `cross_server` | `false` | 是否跨子服显示假人（Carpet 假人 UUID 由名字决定，同名会冲突） |
| `reconcile_interval_ms` | `1000` | 周期对齐间隔 |
| `suppress_removal_ms` | `6000` | 进服后多久内拦截假人删除包 |
| `strip_prefix` | `same_server` | 见 3.2 |
| `format.bot_name` | `%name%` | 假人显示名模板 |
| `format.bot_prefix` / `bot_local` | 空 | 假人前缀模板 |
| `debug` | `false` | 详细日志 |

---

## 4. 聊天模块

### 4.1 功能

- **统一跨服聊天**：`takeover=true` 时由代理接管聊天，带 `【子服名】` 前缀广播到全服
- **跨服事件提示**：加入、离开、**切换子服**
- **跨服 `/msg`**：`/msg`、`/tell`、`/whisper`、`/w`
- **`/chathub`**（别名 `/cb`）：`list` / `msg` / `reload`
- **命令透传**：以 `bypass_prefixes` 开头的消息（默认 `!!,!s,==,@,在哪`）原样放行给子服，供 MCDR 解析

### 4.2 切服提示

```properties
format.switch=【%server_from%】%name% » 【%server_to%】%name%
```

效果：

```
【生存】Sunny_Melor » 【镜像】Sunny_Melor
```

**两个实现细节：**

1. 切服提示广播给**所有人（含切服者本人）**。早期实现把源服、目标服、本人全部排除，导致实际无人可见。
2. 箭头用 **`»`（U+00BB）**，属 Latin-1 Supplement，Minecraft 原版字体自带字形、渲染粗壮。
   不要用 `→`（U+2192，需 Unifont 回退，太细），更不能用 `➡️`（U+27A1 + 变体选择符 `U+FE0F`，后者无字形会渲染成方块）。

### 4.3 聊天配置

`plugins/crossserver/config.properties`

| 键 | 说明 |
| --- | --- |
| `takeover` | 是否由代理接管聊天 |
| `bypass_prefixes` | 透传给子服的消息前缀（逗号分隔） |
| `format.chat` / `join` / `leave` / `switch` | 事件格式模板 |
| `format.list_header` / `list_entry` | `/chathub list` 输出 |
| `format.msg_*` | 私聊相关 |
| `server.*` / `color.server.*` | 子服显示名与颜色 |

占位符：`%server%`、`%server_from%`、`%server_to%`、`%name%`、`%message%`、`%target%`、`%sender%`、`%count%`。

---

## 5. 仓库结构

```
crossserver/
├── src/com/melor/crossserver/
│   ├── CrossServer.java      # 插件主类：聊天、事件、命令
│   └── TabModule.java        # TAB 模块：管线注入、假人、颜色
├── docs/
│   └── release-notes.md
├── .gitignore
├── LICENSE                   # MIT
├── THIRD-PARTY-NOTICES.md
├── README.md
├── build.ps1
└── velocity-plugin.json
```

---

## 6. 构建

需要 **JDK 21+**，以及 Velocity jar 作为编译期 classpath。

```powershell
.\build.ps1
```

脚本会定位 JDK、编译 `src` 下全部源码、打包为 `plugins/CrossServer-<version>.jar`。

> `build.ps1` 里的 `velocityJar` 默认为 `../../velocity-4.3.0-44.jar`，请按实际目录与版本调整。

**注意：** Velocity 运行时会持有插件 jar 的文件句柄，**覆盖前必须先停止代理**，否则会报文件占用。

---

## 7. 安装

1. 将 `CrossServer-<version>.jar` 放入 Velocity 的 `plugins/` 目录
2. 启动代理，首次运行会生成：
   - `plugins/crossserver/config.properties`（聊天）
   - `plugins/crossserver/tab/tab.properties`（TAB）
3. 按需修改配置后重启，或使用 `/chathub reload` 热重载

**环境：**

- Velocity **4.3.0**（需 Java 25 运行时）
- Minecraft **1.21.1**
- 建议搭配 Velocitab（TAB 前缀逻辑依赖其 `【子服】` 格式）

**与 Velocitab 的配合要点：**

`tab_groups.yml` 的 format **不要**用 `<white>` 包裹 `%username%`：

```yaml
# 推荐：颜色可透传
format: 【%server%】%username%

# 不推荐：会压掉子服模组的名字颜色
format: 【%server%】<white>%username%</white>
```

---

## 8. 已验证场景

Velocity 4.3.0 + 三个 Fabric 子服（survival / creative / mirror）+ rankboard 实测：

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
| 切服公屏提示 `【生存】X » 【镜像】X` | ✅ |
| rankboard 榜单色在 TAB 中生效 | ✅ |
| rankboard 榜单色在聊天栏同步生效 | ✅ |

单元测试（`LogicTest` / `ColorTest` / `RealDataTest`）共 **32 项**，使用日志中的真实组件 JSON 校验。

---

## 9. 许可证

**MIT License** — 版权归 **Melor** 所有，详见 [LICENSE](./LICENSE)。

第三方依赖的许可信息见 [THIRD-PARTY-NOTICES.md](./THIRD-PARTY-NOTICES.md)。
