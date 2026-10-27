package com.melor.crossserver;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 跨服核心插件：带子服标签的聊天、跨服事件提示、跨服私聊，以及 TAB 列表的假人与前缀处理。
 *
 * <p>本插件由原 ChatBridge（聊天）与 TabFix（TAB）合并而成。两者面向的功能层不同：</p>
 * <ul>
 *   <li>聊天部分只订阅 {@code PlayerChatEvent} 等事件，被动接收，出错最多影响单条消息。</li>
 *   <li>TAB 部分需要注入 netty 管线，<b>拦截并改写玩家信息包</b>，出错可能影响整条连接。
 *       因此这部分逻辑封装在独立的 {@link TabModule} 中，与聊天逻辑保持隔离。</li>
 * </ul>
 *
 * <p>以 {@code !!} 等前缀开头的消息原样放行给子服（MCDR 指令依赖子服控制台输出解析）。</p>
 */
@Plugin(
        id = "crossserver",
        name = "CrossServer",
        version = "2.1.1",
        description = "Cross-server chat, server switch notices and TAB list handling",
        authors = {"Melor"}
)
public final class CrossServer {

    private static final Pattern PLACEHOLDER = Pattern.compile("%([a-z_]+)%");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put("takeover", "true");
        DEFAULTS.put("bypass_prefixes", "!!,!s,==,@,在哪");
        DEFAULTS.put("format.chat", "【%server%】%name%: %message%");
        DEFAULTS.put("format.join", "【%server%】%name% 加入了游戏");
        DEFAULTS.put("format.leave", "【%server%】%name% 离开了游戏");
        // 箭头用 » (U+00BB)：属 Latin-1，Minecraft 原版字体自带字形，不依赖 Unifont 回退。
        // 别用 → (U+2192) 呈现太细，也别用 ➡️ (U+27A1 + U+FE0F)，变体选择符无字形会渲染成方块
        DEFAULTS.put("format.switch", "【%server_from%】%name% » 【%server_to%】%name%");
        DEFAULTS.put("format.list_header", "当前共有 %count% 名玩家在线:");
        DEFAULTS.put("format.list_entry", "  【%server%】%name%");
        DEFAULTS.put("format.msg_sender", "你悄悄地对 %target% 说: %message%");
        DEFAULTS.put("format.msg_target", "%sender% 悄悄地对你说: %message%");
        DEFAULTS.put("format.msg_offline", "&c玩家 %target% 不在线");
        DEFAULTS.put("format.no_permission", "&c你没有权限执行这个命令");
        DEFAULTS.put("server.survival", "生存");
        DEFAULTS.put("server.creative", "创造");
        DEFAULTS.put("server.mirror", "镜像");
        DEFAULTS.put("color.server.survival", "green");
        DEFAULTS.put("color.server.creative", "gold");
        DEFAULTS.put("color.server.mirror", "aqua");
    }

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final Map<UUID, String> lastServer = new HashMap<>();

    private Properties config = new Properties();
    private List<String> bypassPrefixes = List.of("!!", "!s", "==", "@", "在哪");
    private TabModule tab;

    @Inject
    public CrossServer(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        reload();

        CommandMeta hub = server.getCommandManager().metaBuilder("chathub").aliases("cb").build();
        server.getCommandManager().register(hub, new ChathubCommand());

        CommandMeta msg = server.getCommandManager().metaBuilder("msg")
                .aliases("tell", "whisper", "w").build();
        server.getCommandManager().register(msg, new MsgCommand());
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        tab = new TabModule(server, logger, dataDirectory.resolve("tab"), this);
        tab.start(this);
    }

    // ---------------------------------------------------------------- TAB 模块事件转发
    // TabModule 自己不订阅事件，统一由这里转发，便于在同一插件内保持故障边界清晰

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        handlePostLogin(event.getPlayer());
    }

    @Subscribe
    public void onServerPreConnectForTab(ServerPreConnectEvent event) {
        handleServerPreConnect(event);
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (tab != null) {
            tab.stop();
        }
    }

    /** 供 TAB 模块与聊天模块共用：子服名 → 显示名。 */
    String displayServer(String name) {
        if (name == null) {
            return "?";
        }
        return config.getProperty("server." + name, name);
    }

    /**
     * 取玩家名字在 TAB 上的实际颜色。
     *
     * <p>子服模组（如 rankboard）会把榜单颜色写进 TAB 条目的 displayName，
     * TAB 模块解析时顺手记了下来。聊天栏复用同一个颜色，保证「TAB 里什么色、聊天栏就什么色」。</p>
     *
     * @return 记录的颜色；未知时返回 null（由调用方用默认白色）
     */
    private NamedTextColor nameColorOf(String username) {
        if (tab == null || username == null) {
            return null;
        }
        return tab.nameColorOf(server.getPlayer(username).map(Player::getUniqueId).orElse(null));
    }

    // ---------------------------------------------------------------- config

    private void reload() {
        Path file = dataDirectory.resolve("config.properties");
        Properties props = new Properties();
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(dataDirectory);
                props.putAll(DEFAULTS);
                try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    props.store(writer, "CrossServer config");
                }
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
        } catch (IOException e) {
            logger.error("读取 config.properties 失败，使用默认配置", e);
            props.putAll(DEFAULTS);
        }
        this.config = props;

        List<String> prefixes = new ArrayList<>();
        for (String prefix : config.getProperty("bypass_prefixes", "!!,!s,==,@,在哪").split(",")) {
            String trimmed = prefix.trim();
            if (!trimmed.isEmpty()) {
                prefixes.add(trimmed);
            }
        }
        this.bypassPrefixes = prefixes;
    }

    private String raw(String key) {
        return config.getProperty(key, DEFAULTS.getOrDefault(key, ""));
    }

    private boolean takeover() {
        return Boolean.parseBoolean(config.getProperty("takeover", "true").trim());
    }

    private NamedTextColor serverColor(String name) {
        String color = name == null ? null : config.getProperty("color.server." + name);
        if (color == null) {
            return NamedTextColor.GRAY;
        }
        NamedTextColor named = NamedTextColor.NAMES.value(color.trim().toLowerCase(Locale.ROOT));
        return named == null ? NamedTextColor.GRAY : named;
    }

    // ---------------------------------------------------------------- render

    private Component render(String template, Map<String, Component> placeholders) {
        TextComponent.Builder out = Component.text().color(NamedTextColor.GRAY);
        Matcher matcher = PLACEHOLDER.matcher(template);
        int cursor = 0;
        while (matcher.find()) {
            if (matcher.start() > cursor) {
                out.append(LEGACY.deserialize(template.substring(cursor, matcher.start())));
            }
            Component replacement = placeholders.get(matcher.group(1));
            out.append(replacement != null ? replacement : Component.text(matcher.group(0)));
            cursor = matcher.end();
        }
        if (cursor < template.length()) {
            out.append(LEGACY.deserialize(template.substring(cursor)));
        }
        return out.build();
    }

    private Component serverTag(String name) {
        return Component.text(displayServer(name), serverColor(name));
    }

    /**
     * 玩家名，沿用该玩家在 TAB 上的颜色（rankboard 榜单色等）。
     * 没有记录时退回原版白色。
     */
    private Component playerName(String name) {
        final NamedTextColor color = nameColorOf(name);
        return Component.text(name, color == null ? NamedTextColor.WHITE : color);
    }

    private Map<String, Component> base(String serverName) {
        Map<String, Component> map = new HashMap<>();
        map.put("server", serverTag(serverName));
        return map;
    }

    // ---------------------------------------------------------------- chat

    @Subscribe
    public void onPlayerChat(PlayerChatEvent event) {
        String message = event.getMessage();
        for (String prefix : bypassPrefixes) {
            if (matchesBypass(message, prefix)) {
                // 放行给子服，MCDR 需要从子服控制台读到这条消息
                event.setResult(PlayerChatEvent.ChatResult.allowed());
                return;
            }
        }
        if (!takeover()) {
            return;
        }
        Player player = event.getPlayer();
        Map<String, Component> placeholders = base(serverName(player));
        placeholders.put("name", playerName(player.getUsername()));
        placeholders.put("message", Component.text(message, NamedTextColor.WHITE));
        broadcast(render(raw("format.chat"), placeholders), Collections.emptySet(), null);
        event.setResult(PlayerChatEvent.ChatResult.denied());
    }

    // ---------------------------------------------------------------- join / leave / switch

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        handleServerPostConnect(event);
        Player player = event.getPlayer();
        Optional<String> currentOpt = player.getCurrentServer().map(sc -> sc.getServerInfo().getName());
        if (currentOpt.isEmpty()) {
            return;
        }
        String current = currentOpt.get();
        lastServer.put(player.getUniqueId(), current);

        Set<String> excluded = new LinkedHashSet<>();
        excluded.add(current);

        RegisteredServer previous = event.getPreviousServer();
        if (previous == null) {
            Map<String, Component> placeholders = base(current);
            placeholders.put("name", playerName(player.getUsername()));
            broadcast(render(raw("format.join"), placeholders), excluded, player);
            return;
        }

        String previousName = previous.getServerInfo().getName();
        Map<String, Component> placeholders = new HashMap<>();
        placeholders.put("server_from", serverTag(previousName));
        placeholders.put("server_to", serverTag(current));
        placeholders.put("name", playerName(player.getUsername()));
        // 切服是代理层事件，子服不会自己提示，所以发给所有人（含切服者本人）
        broadcast(render(raw("format.switch"), placeholders), Collections.emptySet(), null);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        handleDisconnect(event.getPlayer());
        Player player = event.getPlayer();
        String last = lastServer.remove(player.getUniqueId());
        if (last == null || event.getLoginStatus() != DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN) {
            return;
        }
        Map<String, Component> placeholders = base(last);
        placeholders.put("name", playerName(player.getUsername()));
        broadcast(render(raw("format.leave"), placeholders), Set.of(last), player);
    }

    // ---------------------------------------------------------------- helpers

    private void broadcast(Component message, Set<String> excludedServers, Player skip) {
        for (Player target : server.getAllPlayers()) {
            if (skip != null && target.getUniqueId().equals(skip.getUniqueId())) {
                continue;
            }
            String targetServer = serverName(target);
            if (targetServer != null && excludedServers.contains(targetServer)) {
                continue;
            }
            target.sendMessage(message);
        }
    }

    private String serverName(Player player) {
        return player.getCurrentServer().map(sc -> sc.getServerInfo().getName())
                .orElseGet(() -> lastServer.get(player.getUniqueId()));
    }

    /**
     * 前缀匹配。以字母或数字结尾的前缀要求后面紧跟空格或消息结束，
     * 这样 "!s" 不会把 "!something" 这类正常聊天也放行。
     */
    private static boolean matchesBypass(String message, String prefix) {
        if (!message.startsWith(prefix)) {
            return false;
        }
        char last = prefix.charAt(prefix.length() - 1);
        if (!Character.isLetterOrDigit(last)) {
            return true;
        }
        return message.length() == prefix.length() || Character.isWhitespace(message.charAt(prefix.length()));
    }

    private Component legacyComponent(String text) {
        return LEGACY.deserialize(text);
    }

    // ---------------------------------------------------------------- commands

    private final class ChathubCommand implements SimpleCommand {

        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            String[] args = invocation.arguments();
            String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "reload" -> {
                    if (!source.hasPermission("crossserver.reload")) {
                        source.sendMessage(legacyComponent(raw("format.no_permission")));
                        return;
                    }
                    reload();
                    if (tab != null) {
                        tab.reload();
                    }
                    source.sendMessage(Component.text("CrossServer 配置已重载", NamedTextColor.GREEN));
                }
                case "msg" -> {
                    if (args.length < 3) {
                        source.sendMessage(Component.text("用法: /chathub msg <玩家> <消息>", NamedTextColor.GRAY));
                        return;
                    }
                    sendPrivate(source, args[1], String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
                }
                default -> sendList(source);
            }
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            String[] args = invocation.arguments();
            if (args.length <= 1) {
                return List.of("list", "msg", "reload");
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("msg")) {
                return onlineNames();
            }
            return Collections.emptyList();
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return true;
        }

        private void sendList(CommandSource source) {
            Map<String, List<String>> grouped = new LinkedHashMap<>();
            for (Player player : server.getAllPlayers()) {
                String name = serverName(player);
                grouped.computeIfAbsent(name == null ? "?" : name, key -> new ArrayList<>())
                        .add(player.getUsername());
            }
            for (List<String> names : grouped.values()) {
                names.sort(String.CASE_INSENSITIVE_ORDER);
            }
            Map<String, Component> countPlaceholders = new HashMap<>();
            countPlaceholders.put("count", Component.text(server.getAllPlayers().size(), NamedTextColor.YELLOW));
            source.sendMessage(render(raw("format.list_header"), countPlaceholders));
            for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
                for (String name : entry.getValue()) {
                    Map<String, Component> placeholders = base(entry.getKey());
                    placeholders.put("name", playerName(name));
                    source.sendMessage(render(raw("format.list_entry"), placeholders));
                }
            }
        }
    }

    private final class MsgCommand implements SimpleCommand {

        @Override
        public void execute(Invocation invocation) {
            String[] args = invocation.arguments();
            if (args.length < 2) {
                invocation.source().sendMessage(Component.text("用法: /msg <玩家> <消息>", NamedTextColor.GRAY));
                return;
            }
            sendPrivate(invocation.source(), args[0], String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            if (invocation.arguments().length <= 1) {
                return onlineNames();
            }
            return Collections.emptyList();
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return true;
        }
    }

    private List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        for (Player player : server.getAllPlayers()) {
            names.add(player.getUsername());
        }
        return names;
    }

    private void sendPrivate(CommandSource source, String targetName, String message) {
        Optional<Player> targetOpt = server.getPlayer(targetName);
        if (targetOpt.isEmpty()) {
            Map<String, Component> placeholders = new HashMap<>();
            placeholders.put("target", Component.text(targetName, NamedTextColor.WHITE));
            source.sendMessage(render(raw("format.msg_offline"), placeholders));
            return;
        }
        Player target = targetOpt.get();
        String senderName = source instanceof Player player ? player.getUsername() : "Console";

        Map<String, Component> senderView = new HashMap<>();
        senderView.put("target", playerName(target.getUsername()));
        senderView.put("message", Component.text(message, NamedTextColor.WHITE));
        source.sendMessage(render(raw("format.msg_sender"), senderView));

        Map<String, Component> targetView = new HashMap<>();
        targetView.put("sender", Component.text(senderName, NamedTextColor.WHITE));
        targetView.put("message", Component.text(message, NamedTextColor.WHITE));
        target.sendMessage(render(raw("format.msg_target"), targetView));
    }

    // ---------------------------------------------------------------- hooks for TabModule

    /** TAB 模块在玩家登录时调用，用于注入管线。 */
    void handlePostLogin(Player player) {
        if (tab != null) {
            tab.onPostLogin(player);
        }
    }

    /** TAB 模块在玩家断开时调用，用于清理状态。 */
    void handleDisconnect(Player player) {
        if (tab != null) {
            tab.onDisconnect(player);
        }
    }

    /** 切服前给 TAB 模块开快照窗口。 */
    void handleServerPreConnect(ServerPreConnectEvent event) {
        if (tab != null) {
            tab.onServerPreConnect(event);
        }
    }

    /** 切服后通知 TAB 模块重新对齐。 */
    void handleServerPostConnect(ServerPostConnectEvent event) {
        if (tab != null) {
            tab.onServerPostConnect(event);
        }
    }
}
