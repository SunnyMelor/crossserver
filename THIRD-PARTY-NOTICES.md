# 第三方版权与许可声明

本仓库（`crossserver`）中的全部源代码均为 **Melor 的原创作品**，采用 **MIT** 许可证。

- **不存在上游项目**：本项目不是任何现有插件的 fork 或二次开发。
- **不包含第三方源代码**：仓库内不含任何第三方项目的代码片段或二进制制品。
- 下列依赖仅在**编译期**或**运行时**通过公开 API 被调用，版权归各自作者所有。

---

## 运行时 / 编译期依赖

### Velocity Proxy

- **作者**：VelocityPowered
- **仓库**：https://github.com/PaperMC/Velocity
- **许可证**：**GPL-3.0**
- **使用方式**：作为插件宿主，本项目仅依赖其公开 API（`com.velocitypowered.api.*`）以及部分内部类
  （`ConnectedPlayer`、`Connections`、`UpsertPlayerInfoPacket` 等，用于实现 TAB 包级操作）。

本项目在编译时需要 Velocity 的 jar 作为 classpath；运行时由 Velocity 自身提供。

> **说明**：本项目**不包含** Velocity 的任何代码，也未修改 Velocity。
> 由于本项目以独立插件形式运行、通过公开/半公开 API 交互而非链接形成衍生作品，
> 以 MIT 独立发布。若你对这一判断有疑问，请咨询原作者。

### Velocitab

- **作者**：William278 & AlexDev03
- **仓库**：https://github.com/William278/Velocitab
- **许可证**：**Apache-2.0**
- **使用方式**：与本插件**并行运行**，协同处理 TAB 列表。

本项目的 TAB 模块在实现时，通过反编译其字节码**分析运行时行为**，用于理解：

- `PlayerChannelHandler#forceGameMode` — 对旁观者（`gameMode == 3`）的改写规则
- `PlayerTabList#updateTabListOnJoin` — 进服时删除「非代理玩家」条目的行为
- `TabPlayer` 的字段构成 — 确认其不读取子服 displayName，从而定位颜色丢失原因

**注意：** 这是对**运行时行为**的观察与兼容性适配，**没有复制、修改或再分发**
Velocitab 的源代码。本项目不包含 Velocitab 的任何代码片段。
Apache-2.0 允许此类互操作性实现，无需额外授权。

### SignedVelocity

- **作者**：4drian3d
- **仓库**：https://github.com/4drian3d/SignedVelocity
- **许可证**：**LGPL-3.0**
- **使用方式**：与本插件并行运行，本项目未调用其 API。

### rankboard（可选，仅在子服侧）

- **作者**：bamgdam
- **来源**：Modrinth / 作者发布页
- **使用方式**：运行于**子服**（Fabric 模组），与本插件无代码依赖。

本项目的「保留子服模组名字颜色」功能是为兼容此类模组而设计（不限于 rankboard）。
同样仅为**行为层面的适配**：通过反编译分析其 mixin 目标（`getTabListDisplayName`）
确认颜色写入位置，未复制其任何代码。

### Minecraft / Fabric

- **Minecraft** © Mojang Studios / Microsoft
- **Fabric Loader / Fabric API** © FabricMC
- **许可证**：各自专有 / Apache-2.0

本仓库**不包含**上述任何专有资源（贴图、音效、语言文件等）。

---

## 关于反向工程的说明

为定位故障根因，开发过程中使用 `javap` 等工具对**运行时依赖的字节码**做了**行为分析**，
用于理解包流转顺序、TAB 条目构造方式与颜色继承规则。

这些分析仅用于**理解互操作行为**，**未产生任何衍生源代码**。相关结论以技术文档形式
记录于 [README.md](./README.md) 与 [docs/release-notes.md](./docs/release-notes.md)。

若你是上述项目的作者，认为本仓库有不妥之处，请提 Issue，将立即处理。

---

## 本仓库许可

**MIT License** — 版权归 **Melor** 所有，详见 [LICENSE](./LICENSE)。
