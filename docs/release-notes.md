# Release Notes

## v2.1.1 — 2026-10-28

**修复：子服模组（rankboard 等）的名字颜色被 Velocitab 覆盖**

*现象：* 玩家切换排行榜后，TAB 和聊天栏的 ID 颜色均不变。

*根因（由日志实证）：* 颜色其实成功到达了代理，但是被随后覆盖。

```
① 05:37:19  子服 rankboard 的蓝色到达代理
   {"color":"blue","text":"Sunny_Melor"}

② 05:37:20  Velocitab 用 %username% 重建显示名，颜色被冲掉
   {"extra":[{"color":"green","text":"生存"},"】Sunny_Melor"],"text":"【"}
```

`format_update_rate: 1000` 使 Velocitab 每秒重建 TAB 条目，而 `%username%` 只是纯用户名
（`TabPlayer` 无读取子服 displayName 的字段），因此每次都把颜色重置为默认。

*修复：*

- 在出站最后一道拦截：先记录子服给玩家名的颜色（`nameColors`），
  每个包发出前若发现颜色被冲掉就贴回去
- `matchesColor()` 保证颜色已正确时不重复改包
- 旁观者仍优先渲染为灰色
- 聊天模块读取同一份 `nameColors`，实现 TAB 与聊天颜色自动一致

*配套配置调整：*

```yaml
# tab_groups.yml —— 去掉 <white>，否则会压掉子服模组的颜色
format: 【%server%】%username%
```

---

## v2.1.0 — 2026-10-28

**改进：切服提示箭头样式**

箭头由 `→`（U+2192）改为 `»`（U+00BB）。后者属 Latin-1 Supplement，
Minecraft 原版字体自带字形，渲染为粗壮实心箭头，无需 Unifont 回退。

> 切勿使用 `➡️`（U+27A1 + 变体选择符 U+FE0F）：`U+FE0F` 无字形，客户端会渲染成方块。

---

## v2.0.0 — 2026-10-28

**重构：TabFix + ChatBridge 合并为单一插件 CrossServer**

两个插件本属同一跨服架构，合并后减少配置重复（原先各自定义了一套子服名映射）。

*架构：*

- `CrossServer` — 聊天、事件、命令
- `TabModule` — TAB 管线操作，独立成类以保持故障边界：
  TAB 在 netty `write()` 内工作，异常会顶掉整条连接；聊天只订阅事件，影响面小

*精简（移除过度设计）：*

原先为「在无人观察某子服时也能知道它的假人」而设计了：

- `bots.dat` 磁盘持久化
- `refreshFromPing()` 后端 status ping 校准
- 进服快照复核（`Snapshot`）
- `lastSeen` 心跳超时剔除
- `cacheTtl`

这些机制仅在 `cross_server=true` 时有意义。而默认配置为 `false`，此时：

- 其他子服的假人缓存永远不会被读取（`desiredFor()` 直接跳过）
- 假人只在「该子服有真人观察」时才会下发，因此按心跳超时剔除会误清无人观察的子服缓存，
  反而复现原 bug

v2.0.0 移除了上述机制，只保留当前视图所需的缓存。缓存未命中时视为空，
子服每次进服都会重发完整列表，自然重建。

*修复的遗留缺陷：*

- `Scheduler#buildTask(Object, ...)` 需要传入插件实例，原实现传 `new Object()`
  会破坏任务归属与取消
- 移除无意义的自比较判断

---

## v1.3.1 — 2026-10-28

**修复：旁观者在他人 TAB 中不显示为灰色**

*根因（两处）：*

1. Velocitab 的 `remove_spectator_effect: true` — `PlayerChannelHandler#forceGameMode` 逻辑：
   ```
   过滤: profileId != null && gameMode == 3 && !profileId.equals(观察者自己)
   动作: entry.setGameMode(0)   // 3(旁观) → 0(生存)
   ```
   除观察者自己外，所有旁观者被改写为生存模式。

2. 名称重写逻辑返回无颜色组件，覆盖了 Velocitab 的 `<white>%username%</white>`，
   即使关闭上一项也会把灰色刷白。

*修复：* 读取条目真实 gameMode，`3` 时使用灰色；部署侧将
`remove_spectator_effect` 置为 `false`。

---

## v1.3.0 — 2026-10-25

**修复：切换子服 / 重新进服后假人从 TAB 消失**

*根因：* 早期实现用自身维护的「已发送」集合记录每个观察者「已发送过哪些假人」。
Velocity 的 `doSwitch()` 在切服时会 `clearAllSilent()` 清空客户端 TAB，
而该集合只在 `ServerPostConnectEvent` 时失效，两者存在时序竞态 ——
集合认为「已显示」而客户端实际为空，补发列表恒为空，假人永不回来。

*修复要点：*

- 以 `TabList#containsEntry(UUID)` 作为唯一事实来源
- 新增 `BackendWatcher`：子服连接上的只读观察者，记录子服**自己**发出的删除包；
  `filterRemoval()` 据此区分「假人真下线」与「Velocitab 误删」
- 修正真人玩家被误缓存为假人的缺陷
- 所有 TAB 写操作改在玩家自己的 `eventLoop()` 执行
- `getState() != PLAY`（切服 CONFIG 阶段）时跳过对齐

### ChatBridge 时期

**新增：切换子服时公屏提示**

格式：`【%server_from%】%name% » 【%server_to%】%name%`

- 广播范围修正为全体玩家（含切服者本人）；原实现把源服、目标服、本人全部排除，
  导致实际无人可见

---

## 更早版本

TabFix 1.2.0 及更早、ChatBridge 1.0.0 未在本仓库保留源码。
