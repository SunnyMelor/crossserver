package com.melor.tabfix;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.util.GameProfile;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.network.Connections;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.RemovePlayerInfoPacket;
import com.velocitypowered.proxy.protocol.packet.UpsertPlayerInfoPacket;
import com.velocitypowered.proxy.protocol.packet.UpsertPlayerInfoPacket.Action;
import com.velocitypowered.proxy.protocol.packet.UpsertPlayerInfoPacket.Entry;
import com.velocitypowered.proxy.protocol.packet.chat.ComponentHolder;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 子服假人（Carpet /player 之类"不是代理玩家"的条目）的 TAB 修复，以及按观察者裁剪真人玩家的服务器前缀。
 *
 * <p>假人不是代理玩家，只能靠子服下发的 PlayerInfo 出现在 TAB。Velocitab 在玩家进服时
 * （PlayerTabList#updateTabListOnJoin）会遍历玩家实际的 TAB 条目，把"不在代理玩家列表里"
 * 的条目统统 removeEntry，于是假人一进服就被删掉，只剩子服每 30 秒发的 UPDATE_LATENCY。</p>
 *
 * <p>1.3.0 的关键改动：</p>
 * <ul>
 *   <li>"客户端到底有没有这条"只以 {@link TabList#containsEntry(UUID)} 为唯一事实来源，
 *       不再自己维护 shown 集合——那个集合在切服后与真实状态脱节，正是"切服/重进后假人消失"的根因。</li>
 *   <li>进服窗口内直接拦下 Velocitab 对假人的删除包，避免"删掉再补"的闪烁与竞态。</li>
 *   <li>所有 TAB 写操作都跑在玩家自己的 netty 事件循环里。</li>
 *   <li>真人玩家：同服/自己显示原版白名，只有别的子服的玩家才保留服务器前缀。</li>
 * </ul>
 */
@Plugin(
        id = "tabfix",
        name = "TabFix",
        version = "1.3.1",
        description = "Keeps server-side fake players in the tab list and trims server tags per viewer",
        authors = {"Melor"}
)
public final class TabFix {

    private static final String HANDLER_NAME = "tabfix";
    private static final String BACKEND_HANDLER_NAME = "tabfix-backend";
    /** Minecraft 协议里旁观者的 gameMode 值。 */
    private static final int SPECTATOR_GAME_MODE = 3;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    /** 标记"当前线程正在写本插件自己的包"，避免被自己的 handler 二次处理。 */
    private static final ThreadLocal<Boolean> REPLAYING = ThreadLocal.withInitial(() -> Boolean.FALSE);
    /**
     * 进服快照里缺席、且这么久都没收到心跳的假人，才算真的下线。
     *
     * <p>只用进服快照做判据：心跳包只有在有人观察该子服时才会下发，
     * 单看"多久没心跳"会把没人观察的子服缓存误清，反而复现原来的 bug。</p>
     */
    private static final long STALE_GRACE_MS = 45_000L;

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    /** serverName -> uuid -> 假人条目 */
    private final Map<String, Map<UUID, BotEntry>> cache = new ConcurrentHashMap<>();
    /** 玩家进/切服时间，用于识别"进服窗口内"的删除包 */
    private final Map<UUID, Long> lastServerJoin = new ConcurrentHashMap<>();
    /** 进服快照：viewerUuid -> 该观察者本次进服看到的完整玩家列表 */
    private final Map<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();
    /** 需要重算真人玩家显示名的观察者 */
    private final Set<UUID> pendingNameRefresh = ConcurrentHashMap.newKeySet();
    /** 上一次观察到的玩家所在子服，用于发现切服 */
    private final Map<UUID, String> lastKnownServer = new ConcurrentHashMap<>();
    /**
     * 由子服自己发出的"删除玩家条目"记录（uuid -> 子服名 + 时间）。
     *
     * <p>客户端链路上分不清这个删除包是子服发的（假人真的走了），还是 Velocitab 主动
     * {@code TabList.removeEntry} 造的（误删）。所以额外在后端连接上观察：子服真的发了，
     * 才允许这个删除通过并清掉缓存；否则一律当作误删拦下。</p>
     */
    private final Map<UUID, AuthoritativeRemoval> authoritativeRemovals = new ConcurrentHashMap<>();

    private boolean replay;
    private boolean crossServer;
    private boolean debug;
    private long reconcileMs;
    private long replayDelayMs;
    private long cacheTtlMs;
    private long suppressRemovalMs;
    private long snapshotMs;
    private long pingIntervalSeconds;
    private String stripPrefix;
    private String botPrefixFormat;
    private String botLocalFormat;
    private String botNameFormat;
    private final Map<String, String> serverNames = new LinkedHashMap<>();

    private ScheduledTask reconcileTask;
    private ScheduledTask saveTask;
    private ScheduledTask pingTask;

    @Inject
    public TabFix(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        reload();
        loadCache();

        logger.info("TabFix 已启用（replay={} crossServer={} reconcile={}ms ttl={}s strip={}）",
                replay, crossServer, reconcileMs, cacheTtlMs / 1000, stripPrefix);
    }

    /** 调度器要求插件已注册，所以任务放在初始化完成后再启动。 */
    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        reconcileTask = server.getScheduler()
                .buildTask(this, this::reconcileAll)
                .repeat(Duration.ofMillis(reconcileMs))
                .schedule();
        saveTask = server.getScheduler()
                .buildTask(this, this::saveCache)
                .repeat(Duration.ofSeconds(60))
                .schedule();

        if (pingIntervalSeconds > 0) {
            pingTask = server.getScheduler()
                    .buildTask(this, this::refreshFromPing)
                    .repeat(Duration.ofSeconds(pingIntervalSeconds))
                    .schedule();
            server.getScheduler()
                    .buildTask(this, this::refreshFromPing)
                    .delay(Duration.ofSeconds(2))
                    .schedule();
        }
    }

    /**
     * 用后端的服务器列表 ping 引导假人缓存。
     *
     * <p>假人条目只有在该子服有真实玩家时才会下发，所以单靠包嗅探，代理刚启动时
     * 无法知道其他子服有哪些假人。status 响应里带了玩家样本，可以用来补齐
     * （需要服务端 hide-online-players=false，且样本未被匿名化）。</p>
     */
    private void refreshFromPing() {
        for (RegisteredServer registered : server.getAllServers()) {
            final String serverName = registered.getServerInfo().getName();
            registered.ping().thenAccept(ping -> {
                try {
                    applyPing(serverName, ping);
                } catch (RuntimeException e) {
                    if (debug) {
                        logger.warn("[debug] 处理 {} 的 ping 结果失败: {}", serverName, e.getMessage());
                    }
                }
            }).exceptionally(error -> {
                if (debug) {
                    logger.warn("[debug] ping {} 失败: {}", serverName, error.getMessage());
                }
                return null;
            });
        }
    }

    private void applyPing(String serverName, ServerPing ping) {
        final Optional<ServerPing.Players> players = ping.getPlayers();
        if (players.isEmpty()) {
            return;
        }
        final List<ServerPing.SamplePlayer> sample = players.get().getSample();
        if (sample == null) {
            return;
        }
        final Map<UUID, BotEntry> perServer =
                cache.computeIfAbsent(serverName, key -> new ConcurrentHashMap<>());
        final Set<UUID> seen = new HashSet<>();
        final long now = System.currentTimeMillis();
        boolean anonymous = false;

        for (ServerPing.SamplePlayer player : sample) {
            final String name = player.getName();
            final UUID id = player.getId();
            // 服务端可能把样本匿名化（名称 Anonymous Player / UUID 全零），这种条目无法使用
            if (name == null || id == null || "Anonymous Player".equals(name)
                    || (id.getMostSignificantBits() == 0L && id.getLeastSignificantBits() == 0L)) {
                anonymous = true;
                continue;
            }
            if (isProxyPlayer(id, name)) {
                continue;
            }
            seen.add(id);
            final BotEntry existing = perServer.get(id);
            if (existing == null) {
                perServer.put(id, new BotEntry(id, name, null, 0, 0));
                if (debug) {
                    logger.info("[debug] ping 发现 {} 的假人: {}", serverName, name);
                }
            } else {
                existing.lastSeen = now;
            }
        }

        // 只有样本完整且未被匿名化时，才敢认定缺席的假人已下线
        if (players.get().getOnline() <= sample.size() && (anonymous ? sample.isEmpty() : true)) {
            perServer.keySet().removeIf(id -> !seen.contains(id));
        } else if (debug && anonymous) {
            logger.info("[debug] {} 的 ping 样本被匿名化，跳过缓存校准（不影响包嗅探）", serverName);
        }
    }

    // ---------------------------------------------------------------- config

    private void reload() {
        final Path file = dataDirectory.resolve("config.properties");
        final Properties props = new Properties();
        props.setProperty("replay", "true");
        props.setProperty("cross_server", "false");
        props.setProperty("reconcile_interval_ms", "1000");
        props.setProperty("replay_delay_ms", "1500");
        props.setProperty("cache_ttl_seconds", "0");
        props.setProperty("suppress_removal_ms", "6000");
        props.setProperty("snapshot_seconds", "3");
        props.setProperty("ping_interval_seconds", "0");
        props.setProperty("strip_prefix", "same_server");
        props.setProperty("format.bot_prefix", "");
        props.setProperty("format.bot_local", "");
        props.setProperty("format.bot_name", "%name%");
        props.setProperty("name.survival", "&a生存");
        props.setProperty("name.creative", "&6创造");
        props.setProperty("name.mirror", "&b镜像");
        props.setProperty("debug", "false");
        try {
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    props.load(reader);
                }
            } else {
                Files.createDirectories(dataDirectory);
                try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    props.store(writer, "TabFix config");
                }
            }
        } catch (IOException e) {
            logger.error("读取 config.properties 失败，使用默认配置", e);
        }

        replay = bool(props, "replay", true);
        crossServer = bool(props, "cross_server", false);
        debug = bool(props, "debug", false);
        reconcileMs = Math.max(200, parseLong(props.getProperty("reconcile_interval_ms"), 1000L));
        replayDelayMs = Math.max(0, parseLong(props.getProperty("replay_delay_ms"), 1500L));
        cacheTtlMs = Math.max(0, parseLong(props.getProperty("cache_ttl_seconds"), 0L)) * 1000L;
        suppressRemovalMs = Math.max(0, parseLong(props.getProperty("suppress_removal_ms"), 6000L));
        snapshotMs = Math.max(500, parseLong(props.getProperty("snapshot_seconds"), 3L) * 1000L);
        pingIntervalSeconds = Math.max(0, parseLong(props.getProperty("ping_interval_seconds"), 0L));
        stripPrefix = props.getProperty("strip_prefix", "same_server").trim().toLowerCase();
        botPrefixFormat = props.getProperty("format.bot_prefix", "");
        botLocalFormat = props.getProperty("format.bot_local", "");
        botNameFormat = props.getProperty("format.bot_name", "%name%");

        serverNames.clear();
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("name.")) {
                serverNames.put(key.substring("name.".length()), props.getProperty(key));
            }
        }
    }

    private static boolean bool(Properties props, String key, boolean fallback) {
        return Boolean.parseBoolean(props.getProperty(key, Boolean.toString(fallback)).trim());
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    // ---------------------------------------------------------------- events

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        inject(event.getPlayer());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        final UUID id = event.getPlayer().getUniqueId();
        remove(event.getPlayer());
        lastServerJoin.remove(id);
        pendingNameRefresh.remove(id);
        lastKnownServer.remove(id);
        snapshots.remove(id);
    }

    /**
     * 进服之前就开一个"快照窗口"。子服会在 JoinGame 后把当前完整玩家列表一次性下发，
     * 窗口里出现过的假人说明还活着，缺席的可以放心剔除。
     */
    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        armSnapshot(event.getPlayer());
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        final Player player = event.getPlayer();
        final String serverName = serverNameOf(player);
        if (serverName == null) {
            return;
        }
        lastServerJoin.put(player.getUniqueId(), System.currentTimeMillis());
        armSnapshot(player);
        watchBackend(player);
        // 切服后客户端 TAB 会被重建，立刻和稍后各对齐一次，避免与 Velocitab 的进服清理抢时序
        scheduleReconcile(player, 300L);
        scheduleReconcile(player, replayDelayMs);

        if (debug) {
            logger.info("[debug] {} 进入 {}，开始对齐假人", player.getUsername(), serverName);
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (reconcileTask != null) {
            reconcileTask.cancel();
        }
        if (saveTask != null) {
            saveTask.cancel();
        }
        if (pingTask != null) {
            pingTask.cancel();
        }
        saveCache();
    }

    private void scheduleReconcile(Player player, long delayMs) {
        server.getScheduler()
                .buildTask(this, () -> runOnPlayerLoop(player, () -> reconcile(player)))
                .delay(Duration.ofMillis(Math.max(0L, delayMs)))
                .schedule();
    }

    /** 所有写包的逻辑都必须跑在玩家自己的事件循环里，否则会跨线程操作 netty 链路和 TAB。 */
    private void runOnPlayerLoop(Player player, Runnable action) {
        try {
            ((ConnectedPlayer) player).getConnection().eventLoop().execute(() -> {
                try {
                    action.run();
                } catch (RuntimeException e) {
                    if (debug) {
                        logger.warn("[debug] 对齐 {} 失败: {}", player.getUsername(), e.toString());
                    }
                }
            });
        } catch (RuntimeException e) {
            if (debug) {
                logger.warn("[debug] 提交 {} 的对齐任务失败: {}", player.getUsername(), e.toString());
            }
        }
    }

    // ---------------------------------------------------------------- reconcile

    private void reconcileAll() {
        try {
            runSnapshots();
            sanitizeCache();
            detectServerChanges();
            for (Player player : server.getAllPlayers()) {
                runOnPlayerLoop(player, () -> reconcile(player));
            }
        } catch (RuntimeException e) {
            logger.warn("TabFix 周期对齐失败: {}", e.toString());
        }
    }

    /**
     * 缓存里混进来的真人玩家（进服握手时瞬间查不到）必须清掉，否则会被当成假人反复补发。
     *
     * <p>这里<b>不</b>用"多久没心跳就剔除"做判断：假人只在有真人观察某个子服时才会下发心跳，
     * 没人观察的子服根本收不到包，按时间剔除就会把别的子服的假人缓存清空——正好复现
     * "切服后假人再也不回来"。谁下线了只能靠进服快照（{@link #pruneStale}）来判定。</p>
     */
    private void sanitizeCache() {
        for (Map.Entry<String, Map<UUID, BotEntry>> perServer : cache.entrySet()) {
            perServer.getValue().entrySet().removeIf(item -> {
                final BotEntry bot = item.getValue();
                if (bot.name == null || bot.name.isEmpty()) {
                    return true;
                }
                if (isProxyPlayer(item.getKey(), bot.name)) {
                    if (debug) {
                        logger.info("[debug] 从缓存剔除真人玩家 {}", bot.name);
                    }
                    return true;
                }
                return false;
            });
        }
        // 短命的临时信号，过期就丢，避免无限增长
        final long now = System.currentTimeMillis();
        authoritativeRemovals.entrySet().removeIf(item -> now - item.getValue().at > 2000L);
    }

    private void armSnapshot(Player player) {
        if (player == null) {
            return;
        }
        // 子服会在 JoinGame 后把当前完整玩家列表一次性下发，用这个窗口判断假人是否还活着
        snapshots.put(player.getUniqueId(), new Snapshot(System.currentTimeMillis() + snapshotMs));
    }

    private void runSnapshots() {
        final long now = System.currentTimeMillis();
        for (UUID viewerId : new ArrayList<>(snapshots.keySet())) {
            final Snapshot snapshot = snapshots.get(viewerId);
            if (snapshot == null || now < snapshot.due) {
                continue;
            }
            if (!snapshots.remove(viewerId, snapshot)) {
                continue;
            }
            final Optional<Player> viewer = server.getPlayer(viewerId);
            if (viewer.isEmpty()) {
                continue;
            }
            final String serverName = serverNameOf(viewer.get());
            if (serverName == null) {
                continue;
            }
            pruneStale(serverName, snapshot, now);
        }
    }

    /**
     * 进服快照里没出现过、且已经很久没收到延迟更新的假人，说明它已经不在那个子服了。
     * 快照为空时不做清理 —— 拿不到权威列表就别乱删。
     */
    private void pruneStale(String serverName, Snapshot snapshot, long now) {
        if (snapshot.seen.isEmpty()) {
            return;
        }
        final Map<UUID, BotEntry> perServer = cache.get(serverName);
        if (perServer == null || perServer.isEmpty()) {
            return;
        }
        final int before = perServer.size();
        perServer.entrySet().removeIf(item -> !snapshot.seen.contains(item.getKey())
                && now - item.getValue().lastSeen > STALE_GRACE_MS);
        if (debug && before != perServer.size()) {
            logger.info("[debug] {} 复核后清理 {} 条已下线的假人条目", serverName, before - perServer.size());
        }
    }

    /** 有人换服时，其他人的前缀需要重算（前缀只在"别的子服"时显示）。 */
    private void detectServerChanges() {
        boolean changed = false;
        for (Player player : server.getAllPlayers()) {
            final String current = serverNameOf(player);
            if (current == null) {
                continue;
            }
            final String previous = lastKnownServer.put(player.getUniqueId(), current);
            if (!current.equals(previous)) {
                changed = true;
            }
        }
        if (changed) {
            for (Player player : server.getAllPlayers()) {
                pendingNameRefresh.add(player.getUniqueId());
            }
        }
    }

    /**
     * 以客户端真实的 TAB 列表为准做对齐：缺了补、多了删、需要时重算真人玩家显示名。
     */
    private void reconcile(Player player) {
        if (!replay || !player.isActive()) {
            return;
        }
        final ConnectedPlayer connected = (ConnectedPlayer) player;
        // 切服的 CONFIG 阶段不能往客户端塞 PLAY 包，交给下一次周期对齐
        if (connected.getConnection().getState() != StateRegistry.PLAY) {
            return;
        }
        final TabList tabList = connected.getTabList();
        final String viewerServer = serverNameOf(player);
        final long now = System.currentTimeMillis();
        final Map<UUID, Resolved> desired = desiredFor(viewerServer, now);

        REPLAYING.set(Boolean.TRUE);
        try {
            // 1) 客户端上不属于"真人玩家 + 该显示的假人"的条目全部清掉
            final List<UUID> stale = new ArrayList<>();
            for (TabListEntry entry : new ArrayList<>(tabList.getEntries())) {
                final GameProfile profile = entry.getProfile();
                if (profile == null || profile.getId() == null) {
                    continue;
                }
                final UUID id = profile.getId();
                if (desired.containsKey(id) || isProxyPlayer(id, profile.getName())) {
                    continue;
                }
                stale.add(id);
            }
            for (UUID id : stale) {
                tabList.removeEntry(id);
                if (debug) {
                    logger.info("[debug] 向 {} 移除失效的假人条目 {}", player.getUsername(), id);
                }
            }

            // 2) 缺的假人补上 —— 判据是 TabList 自己记录的真实状态
            final List<String> added = new ArrayList<>();
            for (Map.Entry<UUID, Resolved> item : desired.entrySet()) {
                final UUID id = item.getKey();
                if (tabList.containsEntry(id)) {
                    continue;
                }
                final TabListEntry entry = buildBotEntry(tabList, item.getValue());
                if (entry == null) {
                    continue;
                }
                tabList.addEntry(entry);
                added.add(item.getValue().bot.name);
            }
            if (debug && !added.isEmpty()) {
                logger.info("[debug] 向 {} 补发 {} 条假人条目：{}",
                        player.getUsername(), added.size(), added);
            }

            // 3) 有人换服后重算真人玩家显示名（同服/自己 = 原版白名）
            if (pendingNameRefresh.remove(player.getUniqueId())) {
                refreshNames(player, tabList, viewerServer);
            }
        } finally {
            REPLAYING.set(Boolean.FALSE);
        }
    }

    private Map<UUID, Resolved> desiredFor(String viewerServer, long now) {
        final Map<UUID, Resolved> desired = new LinkedHashMap<>();
        for (Map.Entry<String, Map<UUID, BotEntry>> perServer : cache.entrySet()) {
            final String botServer = perServer.getKey();
            if (!crossServer && !botServer.equals(viewerServer)) {
                continue;
            }
            for (Map.Entry<UUID, BotEntry> item : perServer.getValue().entrySet()) {
                final UUID id = item.getKey();
                final BotEntry bot = item.getValue();
                if (cacheTtlMs > 0 && now - bot.lastSeen > cacheTtlMs) {
                    perServer.getValue().remove(id);
                    continue;
                }
                if (bot.name == null || bot.name.isEmpty()) {
                    continue;
                }
                if (isProxyPlayer(id, bot.name)) {
                    // 已经是代理玩家了，交给 Velocitab，别抢
                    continue;
                }
                final boolean ownServer = botServer.equals(viewerServer);
                // 同名假人在多个子服会有相同 UUID（离线 UUID 由名字决定），优先本服
                if (desired.containsKey(id) && !ownServer) {
                    continue;
                }
                desired.put(id, new Resolved(botServer, bot, ownServer));
            }
        }
        return desired;
    }

    private TabListEntry buildBotEntry(TabList tabList, Resolved resolved) {
        final BotEntry bot = resolved.bot;
        if (bot.name == null || bot.name.isEmpty() || bot.id == null) {
            return null;
        }
        GameProfile profile = bot.profile;
        if (profile == null) {
            profile = new GameProfile(bot.id, bot.name, List.of());
        }
        Component component = LEGACY.deserialize(botNameFormat.replace("%name%", bot.name));
        if (viewerNeedsTag(resolved.ownServer, false)) {
            final String template = resolved.ownServer ? botLocalFormat : botPrefixFormat;
            if (template != null && !template.isBlank()) {
                final String displayServer = serverNames.getOrDefault(resolved.server, resolved.server);
                component = LEGACY.deserialize(template.replace("%server%", displayServer)).append(component);
            }
        }
        return tabList.buildEntry(profile, component, bot.latency, bot.gameMode);
    }

    /**
     * 真人玩家的显示名：同服 / 自己显示原版白名，别的子服保留 Velocitab 给的服务器前缀。
     *
     * <p>旁观者必须保持灰色（客户端按 gameMode==3 渲染），这里绝不能把它改成白名，
     * 否则别的玩家就看不出谁是旁观者了。</p>
     */
    private void refreshNames(Player viewer, TabList tabList, String viewerServer) {
        for (TabListEntry entry : new ArrayList<>(tabList.getEntries())) {
            final GameProfile profile = entry.getProfile();
            if (profile == null || profile.getId() == null) {
                continue;
            }
            final UUID id = profile.getId();
            final Optional<Player> target = server.getPlayer(id);
            if (target.isEmpty()) {
                continue;
            }
            final String targetServer = serverNameOf(target.get());
            final boolean sameServer = viewerServer != null && viewerServer.equals(targetServer);
            final boolean self = id.equals(viewer.getUniqueId());
            if (viewerNeedsTag(sameServer, self)) {
                continue;
            }
            entry.setDisplayName(plainName(target.get().getUsername(), entry.getGameMode()));
        }
    }

    /**
     * 原版白名。旁观者（gameMode 3）用灰色，和其它玩家看到的观感保持一致。
     *
     * @param gameMode 该条目当前的游戏模式，3 表示旁观者
     */
    private static Component plainName(String username, int gameMode) {
        if (gameMode == SPECTATOR_GAME_MODE) {
            return Component.text(username, NamedTextColor.GRAY);
        }
        return Component.text(username);
    }

    /**
     * 观察者是否需要看到目标玩家的服务器前缀。
     *
     * @param sameServer 目标与自己是否在同一子服
     * @param self       目标是否就是观察者本人
     */
    private boolean viewerNeedsTag(boolean sameServer, boolean self) {
        return switch (stripPrefix) {
            case "none" -> true;
            case "self" -> !self;
            default -> !sameServer;
        };
    }

    private String serverNameOf(Player player) {
        final Optional<ServerConnection> current = player.getCurrentServer();
        if (current.isPresent()) {
            return current.get().getServerInfo().getName();
        }
        // 切服过程中 getCurrentServer() 会短暂为空，用正在建连的那个兜底
        final VelocityServerConnection inFlight =
                ((ConnectedPlayer) player).getConnectionInFlightOrConnectedServer();
        return inFlight == null ? null : inFlight.getServerInfo().getName();
    }

    /**
     * 是否真的是代理上的玩家。ID 和名字都要查：进服握手的瞬间 {@code getPlayer(uuid)}
     * 可能暂时查不到，但名字一般还在，用双重判断避免把真人误当成假人。
     */
    private boolean isProxyPlayer(UUID id, String name) {
        if (id != null && server.getPlayer(id).isPresent()) {
            return true;
        }
        return name != null && !name.isEmpty() && server.getPlayer(name).isPresent();
    }

    /** 进服窗口内收到的"删除假人"通常是 Velocitab 的进服清理，不是假人真的走了。 */
    private boolean inSuppressWindow(Player player) {
        final long joinedAt = lastServerJoin.getOrDefault(player.getUniqueId(), 0L);
        return System.currentTimeMillis() - joinedAt < suppressRemovalMs;
    }

    /** 子服是否真的发过这个删除包（而不是 Velocitab 自己删的）。 */
    private boolean isAuthoritativeRemoval(UUID id, String serverName) {
        final AuthoritativeRemoval removal = authoritativeRemovals.get(id);
        if (removal == null) {
            return false;
        }
        if (System.currentTimeMillis() - removal.at > 2000L) {
            authoritativeRemovals.remove(id, removal);
            return false;
        }
        return serverName == null || removal.server.equals(serverName);
    }

    // ---------------------------------------------------------------- pipeline

    private void inject(Player player) {
        try {
            final ConnectedPlayer connectedPlayer = (ConnectedPlayer) player;
            if (connectedPlayer.getConnection().getChannel().pipeline().get(HANDLER_NAME) != null) {
                return;
            }
            connectedPlayer.getConnection()
                    .getChannel()
                    .pipeline()
                    .addBefore(Connections.HANDLER, HANDLER_NAME, new Handler(player));
        } catch (RuntimeException e) {
            logger.warn("注入 TAB 处理链失败: {}", e.toString());
        }
    }

    /**
     * 在子服连接上装一个观察者，记录子服<b>自己</b>发出的删除包。
     *
     * <p>子服连接每切一次服就换一条，所以要在每次连接建立后重新挂。</p>
     */
    private void watchBackend(Player player) {
        try {
            final ConnectedPlayer connectedPlayer = (ConnectedPlayer) player;
            final VelocityServerConnection backend = connectedPlayer.getConnectionInFlightOrConnectedServer();
            if (backend == null) {
                return;
            }
            final String serverName = backend.getServerInfo().getName();
            final io.netty.channel.Channel channel = backend.ensureConnected().getChannel();
            if (channel.pipeline().get(BACKEND_HANDLER_NAME) != null) {
                return;
            }
            // 必须挂在终端 handler 之前，否则解码后的包不会经过这里
            if (channel.pipeline().get(Connections.HANDLER) != null) {
                channel.pipeline().addBefore(Connections.HANDLER, BACKEND_HANDLER_NAME,
                        new BackendWatcher(serverName));
            } else {
                channel.pipeline().addLast(BACKEND_HANDLER_NAME, new BackendWatcher(serverName));
            }
        } catch (RuntimeException e) {
            if (debug) {
                logger.warn("[debug] 挂载子服观察者失败: {}", e.toString());
            }
        }
    }

    /** 只读观察者：子服真的删了谁，才认为谁下线了。 */
    private final class BackendWatcher extends ChannelDuplexHandler {

        private final String serverName;

        private BackendWatcher(String serverName) {
            this.serverName = serverName;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof RemovePlayerInfoPacket packet) {
                final long now = System.currentTimeMillis();
                for (UUID id : packet.getProfilesToRemove()) {
                    authoritativeRemovals.put(id, new AuthoritativeRemoval(serverName, now));
                }
            }
            super.channelRead(ctx, msg);
        }
    }

    private void remove(Player player) {
        try {
            final ConnectedPlayer connectedPlayer = (ConnectedPlayer) player;
            if (connectedPlayer.getConnection().getChannel().pipeline().get(HANDLER_NAME) != null) {
                connectedPlayer.getConnection().getChannel().pipeline().remove(HANDLER_NAME);
            }
        } catch (RuntimeException e) {
            if (debug) {
                logger.warn("[debug] 移除 handler 失败: {}", e.getMessage());
            }
        }
    }

    private final class Handler extends ChannelDuplexHandler {

        private final Player player;

        private Handler(Player player) {
            this.player = player;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (REPLAYING.get()) {
                // 本插件自己写的包，不再进缓存/改名/拦截逻辑
                super.write(ctx, msg, promise);
                return;
            }
            if (msg instanceof UpsertPlayerInfoPacket packet) {
                record(packet);
                trimServerTags(packet);
            } else if (msg instanceof RemovePlayerInfoPacket packet) {
                if (!filterRemoval(packet)) {
                    promise.setSuccess();
                    return;
                }
            }
            super.write(ctx, msg, promise);
        }

        /** 把子服下发过的假人条目收进缓存，并记录进服快照。 */
        private void record(UpsertPlayerInfoPacket packet) {
            final String serverName = serverNameOf(player);
            if (serverName == null) {
                return;
            }
            final boolean adds = packet.containsAction(Action.ADD_PLAYER);
            final long now = System.currentTimeMillis();
            final Map<UUID, BotEntry> perServer =
                    cache.computeIfAbsent(serverName, key -> new ConcurrentHashMap<>());
            final Snapshot snapshot = snapshots.get(player.getUniqueId());

            for (Entry entry : packet.getEntries()) {
                final UUID id = entry.getProfileId();
                if (id == null) {
                    continue;
                }
                final String name = entry.getProfile() != null ? entry.getProfile().getName() : null;
                if (snapshot != null) {
                    snapshot.seen.add(id);
                }
                if (isProxyPlayer(id, name)) {
                    // 真人玩家不进假人缓存；万一之前误收过，顺手清掉
                    if (adds && perServer.remove(id) != null && debug) {
                        logger.info("[debug] {} 是真人玩家，从假人缓存剔除", name);
                    }
                    continue;
                }
                final BotEntry cached = perServer.get(id);
                if (adds && entry.getProfile() != null) {
                    perServer.put(id, new BotEntry(entry));
                    if (cached == null) {
                        logger.info("缓存 {} 的假人条目: {} ({})", serverName, name, id);
                    }
                } else if (cached != null) {
                    // 子服会给在线假人发延迟/模式更新，用它判断假人还活着
                    cached.lastSeen = now;
                    if (packet.containsAction(Action.UPDATE_LATENCY)) {
                        cached.latency = entry.getLatency();
                    }
                    if (packet.containsAction(Action.UPDATE_GAME_MODE)) {
                        cached.gameMode = entry.getGameMode();
                    }
                }
            }
        }

        /** 真实玩家的服务器前缀：对自己 / 同服的观察者去掉，回归原版白名。 */
        private void trimServerTags(UpsertPlayerInfoPacket packet) {
            if ("none".equals(stripPrefix) || !packet.containsAction(Action.UPDATE_DISPLAY_NAME)) {
                return;
            }
            final String viewerServer = serverNameOf(player);
            final ProtocolVersion version = player.getProtocolVersion();

            for (Entry entry : packet.getEntries()) {
                final UUID id = entry.getProfileId();
                if (id == null || entry.getDisplayName() == null) {
                    continue;
                }
                final Optional<Player> target = server.getPlayer(id);
                if (target.isEmpty()) {
                    // 假人的显示名由本插件的补发逻辑决定
                    continue;
                }
                final String targetServer = serverNameOf(target.get());
                final boolean sameServer = viewerServer != null && viewerServer.equals(targetServer);
                final boolean self = id.equals(player.getUniqueId());
                if (viewerNeedsTag(sameServer, self)) {
                    continue;
                }
                entry.setDisplayName(new ComponentHolder(version,
                        plainName(target.get().getUsername(), entry.getGameMode())));
            }
        }

        /**
         * 挡下针对假人的删除包。
         *
         * <p>Velocitab 的进服清理、以及它自己的去重逻辑，都会调用 {@code TabList.removeEntry}
         * 删掉它不认识的条目——假人就是这么消失的。判定依据是<a>子服自己有没有发过这个删除包</a>
         * （见 {@link BackendWatcher}）：子服没发，就一定是 Velocitab 误删，拦下；子服发了，
         * 说明假人真的走了，放行并清缓存。</p>
         *
         * @return false 表示这个包已经没有内容、无需再写出
         */
        private boolean filterRemoval(RemovePlayerInfoPacket packet) {
            final String serverName = serverNameOf(player);
            final Map<UUID, BotEntry> perServer = serverName == null ? null : cache.get(serverName);
            if (perServer == null || perServer.isEmpty()) {
                return true;
            }
            final boolean suppressible = inSuppressWindow(player);
            final List<UUID> keep = new ArrayList<>();
            boolean changed = false;

            for (UUID id : new ArrayList<>(packet.getProfilesToRemove())) {
                final BotEntry bot = perServer.get(id);
                if (bot == null) {
                    keep.add(id);
                    continue;
                }
                final boolean authoritative = isAuthoritativeRemoval(id, serverName);
                if (!authoritative && (suppressible || server.getPlayer(id).isEmpty())) {
                    // 子服没说它走了 -> Velocitab 的误删
                    changed = true;
                    if (debug) {
                        logger.info("[debug] 拦截 {} 对假人 {} 的误删", player.getUsername(), bot.name);
                    }
                    continue;
                }
                perServer.remove(id);
                authoritativeRemovals.remove(id);
                if (debug) {
                    logger.info("[debug] 假人离开 {}: {}", serverName, bot.name);
                }
                keep.add(id);
            }

            if (!changed) {
                return true;
            }
            packet.setProfilesToRemove(keep);
            return !keep.isEmpty();
        }
    }

    // ---------------------------------------------------------------- persistence

    private Path cacheFile() {
        return dataDirectory.resolve("bots.dat");
    }

    private void saveCache() {
        final Path file = cacheFile();
        try {
            Files.createDirectories(dataDirectory);
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(file)))) {
                out.writeInt(2);
                out.writeInt(cache.size());
                for (Map.Entry<String, Map<UUID, BotEntry>> perServer : cache.entrySet()) {
                    out.writeUTF(perServer.getKey());
                    out.writeInt(perServer.getValue().size());
                    for (Map.Entry<UUID, BotEntry> item : perServer.getValue().entrySet()) {
                        final UUID id = item.getKey();
                        final BotEntry bot = item.getValue();
                        out.writeLong(id.getMostSignificantBits());
                        out.writeLong(id.getLeastSignificantBits());
                        out.writeUTF(bot.name == null ? "" : bot.name);
                        out.writeLong(bot.lastSeen);
                        out.writeInt(bot.latency);
                        out.writeInt(bot.gameMode);
                        out.writeBoolean(bot.profile != null);
                        if (bot.profile != null) {
                            final List<GameProfile.Property> properties = bot.profile.getProperties();
                            out.writeInt(properties.size());
                            for (GameProfile.Property property : properties) {
                                out.writeUTF(nullSafe(property.getName()));
                                out.writeUTF(nullSafe(property.getValue()));
                                final String signature = property.getSignature();
                                out.writeBoolean(signature != null);
                                if (signature != null) {
                                    out.writeUTF(signature);
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("保存假人缓存失败: {}", e.getMessage());
        } catch (RuntimeException e) {
            logger.warn("保存假人缓存异常: {}", e.toString());
        }
    }

    private void loadCache() {
        final Path file = cacheFile();
        if (!Files.exists(file)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            final int version = in.readInt();
            if (version != 2) {
                return;
            }
            final int serverCount = in.readInt();
            for (int i = 0; i < serverCount; i++) {
                final String serverName = in.readUTF();
                final int entryCount = in.readInt();
                final Map<UUID, BotEntry> perServer = new ConcurrentHashMap<>();
                for (int j = 0; j < entryCount; j++) {
                    final UUID id = new UUID(in.readLong(), in.readLong());
                    final String name = in.readUTF();
                    final long lastSeen = in.readLong();
                    final int latency = in.readInt();
                    final int gameMode = in.readInt();
                    GameProfile profile = null;
                    if (in.readBoolean()) {
                        final int propertyCount = in.readInt();
                        final List<GameProfile.Property> properties = new ArrayList<>(propertyCount);
                        for (int k = 0; k < propertyCount; k++) {
                            final String propertyName = in.readUTF();
                            final String propertyValue = in.readUTF();
                            final String signature = in.readBoolean() ? in.readUTF() : null;
                            properties.add(new GameProfile.Property(propertyName, propertyValue, signature));
                        }
                        profile = new GameProfile(id, name, properties);
                    }
                    if (name.isEmpty()) {
                        continue;
                    }
                    final BotEntry bot = new BotEntry(id, name, profile, latency, gameMode);
                    // 落盘时间戳早已过期，直接沿用会让"硬过期"立刻把缓存清空。
                    // 重启后谁是活的由进服复核（快照）负责确认，这里先给一次机会。
                    bot.lastSeen = System.currentTimeMillis();
                    perServer.put(id, bot);
                }
                if (!perServer.isEmpty()) {
                    cache.put(serverName, perServer);
                }
            }
            final int total = cache.values().stream().mapToInt(Map::size).sum();
            if (total > 0) {
                logger.info("已载入 {} 条假人缓存（重启后仍可跨服显示）", total);
            }
        } catch (IOException | RuntimeException e) {
            logger.warn("读取假人缓存失败，忽略: {}", e.getMessage());
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    // ---------------------------------------------------------------- cache model

    private record Resolved(String server, BotEntry bot, boolean ownServer) {
    }

    private record AuthoritativeRemoval(String server, long at) {
    }

    private static final class Snapshot {
        private final Set<UUID> seen = ConcurrentHashMap.newKeySet();
        private volatile long due;

        private Snapshot(long due) {
            this.due = due;
        }
    }

    private static final class BotEntry {

        private final UUID id;
        private final String name;
        private final GameProfile profile;
        private volatile long lastSeen;
        private volatile int latency;
        private volatile int gameMode;

        private BotEntry(Entry source) {
            this.id = source.getProfileId();
            this.name = source.getProfile() != null ? source.getProfile().getName() : "";
            this.profile = source.getProfile();
            this.lastSeen = System.currentTimeMillis();
            this.latency = source.getLatency();
            this.gameMode = source.getGameMode();
        }

        private BotEntry(UUID id, String name, GameProfile profile, int latency, int gameMode) {
            this.id = id;
            this.name = name;
            this.profile = profile;
            this.lastSeen = System.currentTimeMillis();
            this.latency = latency;
            this.gameMode = gameMode;
        }
    }
}
