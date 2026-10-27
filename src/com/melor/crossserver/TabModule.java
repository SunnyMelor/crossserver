package com.melor.crossserver;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;
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
 * TAB 列表处理：让子服侧假人稳定显示，并按观察者裁剪真人玩家的子服前缀。
 *
 * <p>假人不是代理玩家，只能靠子服下发的 PlayerInfo 出现在 TAB。Velocitab 在玩家进服时
 * （{@code PlayerTabList#updateTabListOnJoin}）会遍历玩家实际的 TAB 条目，把"不在代理玩家列表里"
 * 的条目统统 {@code removeEntry}，假人因此被删掉。</p>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>"客户端到底有没有这条"只以 {@link TabList#containsEntry(UUID)} 为唯一事实来源，
 *       不再维护自己的"已发送"集合（那正是切服后假人不回来的根因）。</li>
 *   <li>在子服连接上旁挂只读观察者，只有子服<b>自己</b>发出的删除包才视为假人真的下线。</li>
 *   <li>只缓存<b>当前视图需要</b>的假人（{@code cross_server=false} 时即本子服），
 *       不落盘、不依赖心跳超时——子服每次进服都会重发完整列表，缓存可自然重建。</li>
 * </ul>
 */
final class TabModule {

    private static final String HANDLER_NAME = "crossserver-tab";
    private static final String BACKEND_HANDLER_NAME = "crossserver-tab-backend";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    /** 标记"当前线程正在写本模块自己的包"，避免被自己的 handler 二次处理。 */
    private static final ThreadLocal<Boolean> REPLAYING = ThreadLocal.withInitial(() -> Boolean.FALSE);
    /** Minecraft 协议里旁观者的 gameMode 值。 */
    private static final int SPECTATOR_GAME_MODE = 3;

    private final ProxyServer server;
    private final Logger logger;
    private final Path directory;
    /** 插件主类实例，作为调度任务的归属对象。 */
    private final Object plugin;

    /** serverName -> uuid -> 假人条目 */
    private final Map<String, Map<UUID, BotEntry>> cache = new ConcurrentHashMap<>();
    /** 玩家进/切服时间，用于识别"进服窗口内"的删除包 */
    private final Map<UUID, Long> lastServerJoin = new ConcurrentHashMap<>();
    /** 子服自己发过的删除包：uuid -> 记录，用于区分"真下线"与"Velocitab 误删" */
    private final Map<UUID, AuthoritativeRemoval> authoritativeRemovals = new ConcurrentHashMap<>();
    /** 需要重算真人玩家显示名的观察者 */
    private final Set<UUID> pendingNameRefresh = ConcurrentHashMap.newKeySet();
    /** 上一次观察到的玩家所在子服，用于发现切服 */
    private final Map<UUID, String> lastKnownServer = new ConcurrentHashMap<>();
    /** 玩家名在 TAB 上的实际颜色（来自子服模组，如 rankboard 的榜单色） */
    private final Map<UUID, NamedTextColor> nameColors = new ConcurrentHashMap<>();

    private boolean replay;
    private boolean crossServer;
    private boolean debug;
    private long reconcileMs;
    private long suppressRemovalMs;
    private String stripPrefix;
    private String botNameFormat;
    private String botPrefixFormat;
    private String botLocalFormat;

    private ScheduledTask reconcileTask;

    TabModule(ProxyServer server, Logger logger, Path directory, Object plugin) {
        this.server = server;
        this.logger = logger;
        this.directory = directory;
        this.plugin = plugin;
        reload();
    }

    void start(Object plugin) {
        reconcileTask = server.getScheduler()
                .buildTask(plugin, this::reconcileAll)
                .repeat(Duration.ofMillis(reconcileMs))
                .schedule();
        logger.info("TAB 模块已启用（replay={} crossServer={} reconcile={}ms strip={}）",
                replay, crossServer, reconcileMs, stripPrefix);
    }

    void stop() {
        if (reconcileTask != null) {
            reconcileTask.cancel();
        }
        for (Player player : server.getAllPlayers()) {
            removePipeline(player);
        }
    }

    /**
     * 查询玩家名在 TAB 上的实际颜色，供聊天模块复用。
     *
     * @param playerId 玩家 UUID，可为 null
     * @return 颜色；未知时返回 null
     */
    NamedTextColor nameColorOf(UUID playerId) {
        return playerId == null ? null : nameColors.get(playerId);
    }

    // ---------------------------------------------------------------- config

    void reload() {
        Path file = directory.resolve("tab.properties");
        Properties props = new Properties();
        props.setProperty("replay", "true");
        props.setProperty("cross_server", "false");
        props.setProperty("reconcile_interval_ms", "1000");
        props.setProperty("suppress_removal_ms", "6000");
        props.setProperty("strip_prefix", "same_server");
        props.setProperty("format.bot_name", "%name%");
        props.setProperty("format.bot_prefix", "");
        props.setProperty("format.bot_local", "");
        props.setProperty("debug", "false");
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(directory);
                try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    props.store(writer, "CrossServer TAB module config");
                }
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
        } catch (IOException e) {
            logger.error("读取 tab.properties 失败，使用默认配置", e);
        }

        replay = bool(props, "replay", true);
        crossServer = bool(props, "cross_server", false);
        debug = bool(props, "debug", false);
        reconcileMs = Math.max(200, parseLong(props.getProperty("reconcile_interval_ms"), 1000L));
        suppressRemovalMs = Math.max(0, parseLong(props.getProperty("suppress_removal_ms"), 6000L));
        stripPrefix = props.getProperty("strip_prefix", "same_server").trim().toLowerCase();
        botNameFormat = props.getProperty("format.bot_name", "%name%");
        botPrefixFormat = props.getProperty("format.bot_prefix", "");
        botLocalFormat = props.getProperty("format.bot_local", "");
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

    void onPostLogin(Player player) {
        injectPipeline(player);
    }

    void onDisconnect(Player player) {
        final UUID id = player.getUniqueId();
        removePipeline(player);
        lastServerJoin.remove(id);
        pendingNameRefresh.remove(id);
        lastKnownServer.remove(id);
        nameColors.remove(id);
    }

    void onServerPreConnect(ServerPreConnectEvent event) {
        // 子服会在 JoinGame 后把当前完整玩家列表一次性下发，窗口内出现的假人才算还活着
        lastServerJoin.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    void onServerPostConnect(ServerPostConnectEvent event) {
        final Player player = event.getPlayer();
        lastServerJoin.put(player.getUniqueId(), System.currentTimeMillis());
        watchBackend(player);
        scheduleReconcile(player, 300L);
        if (debug) {
            logger.info("[debug] {} 进入 {}，开始对齐假人", player.getUsername(), serverNameOf(player));
        }
    }

    private void scheduleReconcile(Player player, long delayMs) {
        server.getScheduler()
                .buildTask(plugin, () -> runOnPlayerLoop(player, () -> reconcile(player)))
                .delay(Duration.ofMillis(Math.max(0L, delayMs)))
                .schedule();
    }

    /** 所有写包逻辑必须跑在玩家自己的事件循环里，否则会跨线程操作 netty 链路和 TAB。 */
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
            sanitizeCache();
            detectServerChanges();
            for (Player player : server.getAllPlayers()) {
                runOnPlayerLoop(player, () -> reconcile(player));
            }
        } catch (RuntimeException e) {
            logger.warn("TAB 周期对齐失败: {}", e.toString());
        }
    }

    /** 剔除混进缓存的真人玩家，以及长期无心跳的假人。 */
    private void sanitizeCache() {
        for (Map.Entry<String, Map<UUID, BotEntry>> perServer : cache.entrySet()) {
            perServer.getValue().entrySet().removeIf(item -> {
                final BotEntry bot = item.getValue();
                return bot.name == null || bot.name.isEmpty()
                        || isProxyPlayer(item.getKey(), bot.name);
            });
        }
        // 短命的临时信号，过期就丢
        final long now = System.currentTimeMillis();
        authoritativeRemovals.entrySet().removeIf(item -> now - item.getValue().at > 2000L);
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

    /** 以客户端真实的 TAB 列表为准做对齐：缺了补、多了删、需要时重算显示名。 */
    private void reconcile(Player player) {
        if (!replay || !player.isActive()) {
            return;
        }
        final ConnectedPlayer connected = (ConnectedPlayer) player;
        // 切服的 CONFIG 阶段不能往客户端塞 PLAY 包
        if (connected.getConnection().getState() != StateRegistry.PLAY) {
            return;
        }
        final TabList tabList = connected.getTabList();
        final String viewerServer = serverNameOf(player);
        final Map<UUID, Resolved> desired = desiredFor(viewerServer);

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

    private Map<UUID, Resolved> desiredFor(String viewerServer) {
        final Map<UUID, Resolved> desired = new LinkedHashMap<>();
        for (Map.Entry<String, Map<UUID, BotEntry>> perServer : cache.entrySet()) {
            final String botServer = perServer.getKey();
            final boolean ownServer = botServer.equals(viewerServer);
            // cross_server=false 时只显示本服的假人，其它子服的缓存不参与
            if (!crossServer && !ownServer) {
                continue;
            }
            for (Map.Entry<UUID, BotEntry> item : perServer.getValue().entrySet()) {
                final UUID id = item.getKey();
                final BotEntry bot = item.getValue();
                if (bot.name == null || bot.name.isEmpty()) {
                    continue;
                }
                if (isProxyPlayer(id, bot.name)) {
                    // 已经是代理玩家了，交给 Velocitab
                    continue;
                }
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
                component = LEGACY.deserialize(template.replace("%server%", resolved.server)).append(component);
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
            // 保留条目原有颜色（子服模组可能给名字染了色）
            final Component current = entry.getDisplayNameComponent().orElse(null);
            entry.setDisplayName(nameWithOriginalColor(current, target.get().getUsername(), entry.getGameMode()));
        }
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

    /**
     * 只保留玩家名、但沿用原组件上的颜色。
     *
     * <p>子服侧的模组（如 rankboard）会把榜单颜色写进 displayName。Velocitab 套用
     * {@code %username%} 后这是一个多段组件（{@code 【子服】名字}）。裁掉前缀时如果直接
     * 生成新的白名文本，子服给的颜色就丢了，所以这里从原组件里把"名字那一段"的颜色取出来复用。</p>
     *
     * @param original 子服下发 / Velocitab 拼接后的原始组件
     * @param username 目标玩家名
     * @param gameMode 该条目的游戏模式，3 表示旁观者
     */
    private static Component nameWithOriginalColor(Component original, String username, int gameMode) {
        final NamedTextColor inherited = findColorFor(original, username);
        if (gameMode == SPECTATOR_GAME_MODE) {
            // 旁观者优先用灰色，这是客户端识别旁观者的依据
            return Component.text(username, NamedTextColor.GRAY);
        }
        return Component.text(username, inherited);
    }

    /**
     * 在组件树里找出「包含该玩家名」的那一段的颜色。
     *
     * @return 找到的颜色；找不到时返回 null（等价于原版默认色）
     */
    private static NamedTextColor findColorFor(Component root, String username) {
        if (root == null || username == null) {
            return null;
        }
        // 先看根节点：可能是 {text:"名字", color:...}
        if (matchesName(root, username) && root.color() instanceof NamedTextColor named) {
            return named;
        }
        for (Component child : root.children()) {
            if (matchesName(child, username) && child.color() instanceof NamedTextColor named) {
                return named;
            }
        }
        return null;
    }

    private static boolean matchesName(Component component, String username) {
        final String content;
        if (component instanceof net.kyori.adventure.text.TextComponent text) {
            content = text.content();
        } else {
            return false;
        }
        return content != null && content.contains(username);
    }

    /**
     * 判断组件里「名字那一段」是否已经是期望的颜色。
     * 用于避免每个 tick 都重复改包。
     */
    private static boolean matchesColor(Component root, String username, NamedTextColor expected) {
        final NamedTextColor actual = findColorFor(root, username);
        if (actual == null) {
            return false;
        }
        return actual.value() == expected.value();
    }

    private String serverNameOf(Player player) {        final Optional<ServerConnection> current = player.getCurrentServer();
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

    private void injectPipeline(Player player) {
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

    /** 在子服连接上挂只读观察者，记录子服自己发出的删除包。 */
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

    private void removePipeline(Player player) {
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

    private final class Handler extends ChannelDuplexHandler {

        private final Player player;

        private Handler(Player player) {
            this.player = player;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (REPLAYING.get()) {
                // 本模块自己写的包，不再进缓存/改名/拦截逻辑
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

        /** 把子服下发过的假人条目收进缓存。 */
        private void record(UpsertPlayerInfoPacket packet) {
            final String serverName = serverNameOf(player);
            if (serverName == null) {
                return;
            }
            final boolean adds = packet.containsAction(Action.ADD_PLAYER);
            final long now = System.currentTimeMillis();
            final Map<UUID, BotEntry> perServer =
                    cache.computeIfAbsent(serverName, key -> new ConcurrentHashMap<>());

            for (Entry entry : packet.getEntries()) {
                final UUID id = entry.getProfileId();
                if (id == null) {
                    continue;
                }
                final String name = entry.getProfile() != null ? entry.getProfile().getName() : null;
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
            if (!packet.containsAction(Action.UPDATE_DISPLAY_NAME)) {
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
                    // 假人的显示名由本模块的补发逻辑决定
                    continue;
                }
                final String username = target.get().getUsername();

                // ① 先把子服（rankboard 等）给这个名字用的颜色记下来
                final NamedTextColor observed = findColorFor(
                        entry.getDisplayName().getComponent(), username);
                if (observed != null) {
                    nameColors.put(id, observed);
                }

                // ② Velocitab 每秒会用 %username% 重建显示名，把子服颜色冲掉。
                //    这里用记下的颜色把它补回去（旁观者仍由客户端按 gameMode 渲染为灰色）
                final NamedTextColor restored = nameColors.get(id);
                if (restored != null && !matchesColor(entry.getDisplayName().getComponent(), username, restored)) {
                    entry.setDisplayName(new ComponentHolder(version,
                            entry.getGameMode() == SPECTATOR_GAME_MODE
                                    ? Component.text(username, NamedTextColor.GRAY)
                                    : Component.text(username, restored)));
                    if (debug) {
                        logger.info("[debug] 为 {} 补回颜色 {}", username, restored);
                    }
                }

                if ("none".equals(stripPrefix)) {
                    continue;
                }
                final String targetServer = serverNameOf(target.get());
                final boolean sameServer = viewerServer != null && viewerServer.equals(targetServer);
                final boolean self = id.equals(player.getUniqueId());
                if (viewerNeedsTag(sameServer, self)) {
                    continue;
                }
                // 关键：裁剪前缀时保留玩家名的颜色，不要刷成白名
                final Component renamed = nameWithOriginalColor(
                        entry.getDisplayName().getComponent(), username, entry.getGameMode());
                entry.setDisplayName(new ComponentHolder(version, renamed));
            }
        }

        /**
         * 挡下针对假人的误删。
         *
         * <p>Velocitab 的进服清理、以及它自己的去重逻辑，都会调用 {@code TabList.removeEntry}
         * 删掉它不认识的条目——假人就是这么消失的。判定依据是子服自己有没有发过这个删除包
         * （见 {@link BackendWatcher}）：子服没发就一定是误删，拦下；子服发了说明假人真的走了。</p>
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

    // ---------------------------------------------------------------- model

    private record Resolved(String server, BotEntry bot, boolean ownServer) {
    }

    private record AuthoritativeRemoval(String server, long at) {
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
    }
}
