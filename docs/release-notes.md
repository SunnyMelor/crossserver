# Release Notes

## v1.3.1 — 2026-10-28

### TabFix

**修复：旁观者（Spectator）在他人 TAB 中不显示为灰色**

*问题现象：* 玩家进入旁观者模式后，只有自己看到自己的 ID 变灰，其他玩家看到的仍是白色，无法判断谁在旁观。

*根因（两处）：*

1. **Velocitab 的 `remove_spectator_effect: true`** — 反编译 `PlayerChannelHandler#forceGameMode` 可见其逻辑：
   ```
   过滤: profileId != null && gameMode == 3 && !profileId.equals(观察者自己)
   动作: entry.setGameMode(0)   // 3(旁观) → 0(生存)
   ```
   它把除观察者自己以外所有旁观者改写成生存模式，客户端据此按普通玩家渲染。

2. **TabFix 的 `plainName()` 丢失颜色** — 原实现返回无颜色组件，覆盖了 Velocitab 的 `<white>%username%</white>` 格式，即使前一项关闭也会把灰色刷回白色。

*修复：*

- `plainName(username, gameMode)` 改为读取条目真实 gameMode，`3` 时使用 `NamedTextColor.GRAY`
- `refreshNames()`、`trimServerTags()` 两处调用均传入 `entry.getGameMode()`
- 部署侧将 Velocitab 的 `remove_spectator_effect` 置为 `false`

---

## v1.3.0 — 2026-10-25

### TabFix

**修复：切换子服 / 重新进服后假人从 TAB 消失**

*根因：* 1.2.0 使用自身维护的 `shown` 集合（每个观察者「已发送过哪些假人」）。
Velocity 的 `doSwitch()` 在切服时会 `clearAllSilent()` 清空客户端 TAB，而 `shown`
只在 `ServerPostConnectEvent` 时失效——两者存在时序竞态，导致 `shown` 认为「已显示」
而客户端实际为空，`toAdd` 恒为空，假人永不回来。

*修复要点：*

- 以 `TabList#containsEntry(UUID)` 作为「客户端是否真有该条目」的唯一事实来源
- 新增 `BackendWatcher`：在子服连接上旁挂只读 handler，记录子服**自己**发出的
  `RemovePlayerInfoPacket`；`filterRemoval()` 据此区分「假人真下线」与「Velocitab 误删」
- 修正真人玩家被误缓存为假人的缺陷（进服握手瞬间 `getPlayer(uuid)` 为空）
- 所有 TAB 写操作改在 `player.getConnection().eventLoop()` 执行
- `getState() != PLAY`（切服 CONFIG 阶段）时跳过对齐
- 移除基于「心跳超时」的缓存清理——假人仅在有人观察该子服时才收到心跳，
  按时间剔除会误清无人观察的子服缓存，反而复现原 bug

### ChatBridge

**新增：切换子服时公屏提示**

格式：`【%server_from%】%name% → 【%server_to%】%name%`

- 广播范围修正为**全体玩家（含切服者本人）**；原实现把源服、目标服、
  本人全部排除，导致实际无人可见
- 箭头使用 **U+2192 `→`**：`➡️`（`U+27A1` + 变体选择符 `U+FE0F`）
  中的 `U+FE0F` 在默认字体无字形，客户端会渲染为方块

---

## v1.2.0 及更早

历史版本，未在本仓库保留源码。
