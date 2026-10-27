# 第三方版权与许可声明

本仓库（`tabfix-chatbridge`）中的 **TabFix** 与 **ChatBridge** 全部源代码均为
**Melor 的原创作品**，采用 **MIT** 许可证。

- **不存在上游项目**：本项目不是任何现有插件的 fork 或二次开发。
- **不包含第三方源代码**：仓库内不含任何第三方项目的代码片段或二进制制品。
- 下列依赖仅在**编译期**或**运行时**被调用（通过公开 API），其版权归各自作者所有。

---

## 运行时/编译期依赖

### Velocity Proxy

- **作者**：VelocityPowered
- **仓库**：https://github.com/PaperMC/Velocity
- **许可证**：**GPL-3.0**
- **使用方式**：作为插件宿主，本项目仅依赖其公开 API（`com.velocitypowered.api.*`）。

本项目在编译时需要 Velocity 的 jar 作为 classpath；运行时由 Velocity 自身提供。

> 说明：本项目**不包含** Velocity 的任何代码，也未修改 Velocity。
> 由于本项目仅通过公开 API 交互、未链接 GPL 代码形成衍生作品，
> 本项目以 MIT 独立发布。若你对这一判断有疑问，请咨询原作者。

### Velocitab

- **作者**：William278 & AlexDev03
- **仓库**：https://github.com/William278/Velocitab
- **许可证**：**Apache-2.0**
- **使用方式**：与本插件**并行运行**，协同处理 TAB 列表。

TabFix 的实现参考了 Velocitab 的行为特征（通过反编译其字节码分析
`PlayerChannelHandler#forceGameMode`、`PlayerTabList#updateTabListOnJoin`
等方法的**行为**），并据此设计拦截逻辑。

**注意：** 这是对**运行时行为**的观察与适配，**没有复制、修改或再分发**
Velocitab 的源代码。本项目不包含 Velocitab 的任何代码片段。

Apache-2.0 允许此类互操作性实现，无需额外授权。

### SignedVelocity

- **作者**：4drian3d
- **仓库**：https://github.com/4drian3d/SignedVelocity
- **许可证**：**LGPL-3.0**
- **使用方式**：与本插件并行运行，本项目未调用其 API。

### Minecraft / Fabric

- **Minecraft** © Mojang Studios / Microsoft
- **Fabric Loader / Fabric API** © FabricMC
- **许可证**：各自专有 / Apache-2.0

本仓库**不包含**上述任何专有资源（贴图、音效、语言文件等）。

---

## 关于「反向工程」的说明

为定位 TAB 列表假人消失的根因，开发过程中使用了 `javap` 等工具对
**运行时依赖的字节码**进行了**行为分析**，用于理解：

1. `ClientPlaySessionHandler#doSwitch` 清空 TAB 的时机
2. `Velocitab#PlayerChannelHandler#write` 对 `UpsertPlayerInfoPacket` 的处理
3. `Velocitab#forceGameMode` 对旁观者（`gameMode == 3`）的改写规则

这些分析仅用于**理解互操作行为**，**未产生任何衍生源代码**。相关结论以技术文档
形式记录于 [README.md](./README.md) 与 [docs/release-notes.md](./docs/release-notes.md)。

若你是上述项目的作者，认为本仓库有不妥之处，请提 Issue，将立即处理。

---

## 本仓库许可

**MIT License** — 版权归 **Melor** 所有，详见 [LICENSE](./LICENSE)。
