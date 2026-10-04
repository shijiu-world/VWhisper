package cn.shijiu.vwhisper;

import org.slf4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家的私聊状态：屏蔽名单、接收开关、窥屏开关、/reply 的上一个对象。
 *
 * <p>纯内存 + 两个纯文本文件（{@code plugins/vwhisper/ignores.txt}、{@code toggles.txt}），
 * 一行一条，肉眼可读、丢了也不影响插件起来。
 * ⚠️ 窥屏（spy）状态故意不落盘 —— 代理一重启就自动关掉，免得哪天忘了关还在偷看。
 */
public final class Store {

    private final Path directory;
    private final Logger logger;

    /** 被谁屏蔽了谁：owner -> targets。 */
    private final Map<UUID, Set<UUID>> ignored = new ConcurrentHashMap<>();
    /** 关掉接收私聊的人。 */
    private final Set<UUID> toggledOff = ConcurrentHashMap.newKeySet();
    /** 正在窥屏的人。 */
    private final Set<UUID> spying = ConcurrentHashMap.newKeySet();
    /** /reply 的对象：我最近一次跟谁聊过。 */
    private final Map<UUID, UUID> lastContact = new ConcurrentHashMap<>();

    public Store(final Path directory, final Logger logger) {
        this.directory = directory;
        this.logger = logger;
    }

    // ------------------------------------------------------------------
    // 屏蔽
    // ------------------------------------------------------------------

    public boolean isIgnoring(final UUID owner, final UUID target) {
        final Set<UUID> set = ignored.get(owner);
        return set != null && set.contains(target);
    }

    /** 切换屏蔽状态；返回 true 表示这次是「新增屏蔽」。 */
    public boolean toggleIgnore(final UUID owner, final UUID target, final boolean save) {
        final Set<UUID> set = ignored.computeIfAbsent(owner, k -> ConcurrentHashMap.newKeySet());
        final boolean added;
        if (set.contains(target)) {
            set.remove(target);
            added = false;
        } else {
            set.add(target);
            added = true;
        }
        if (set.isEmpty()) {
            ignored.remove(owner);
        }
        if (save) {
            saveIgnores();
        }
        return added;
    }

    public Map<UUID, Set<UUID>> ignores() {
        return ignored;
    }

    // ------------------------------------------------------------------
    // 接收开关
    // ------------------------------------------------------------------

    /** 这个人开着接收私聊吗（默认开）。 */
    public boolean isReceiving(final UUID uuid) {
        return !toggledOff.contains(uuid);
    }

    /** 设置是否接收私聊。 */
    public void setReceiving(final UUID uuid, final boolean receiving, final boolean save) {
        if (receiving) {
            toggledOff.remove(uuid);
        } else {
            toggledOff.add(uuid);
        }
        if (save) {
            saveToggles();
        }
    }

    public Set<UUID> toggledOff() {
        return toggledOff;
    }

    // ------------------------------------------------------------------
    // 窥屏
    // ------------------------------------------------------------------

    public boolean isSpying(final UUID uuid) {
        return spying.contains(uuid);
    }

    public void setSpying(final UUID uuid, final boolean spy) {
        if (spy) {
            spying.add(uuid);
        } else {
            spying.remove(uuid);
        }
    }

    // ------------------------------------------------------------------
    // /reply 记忆
    // ------------------------------------------------------------------

    /** A 和 B 刚聊过：两边都记一下，谁都能接着 /r。 */
    public void rememberContact(final UUID a, final UUID b) {
        lastContact.put(a, b);
        lastContact.put(b, a);
    }

    /** 我该回复谁；没聊过就是 null。 */
    public UUID contact(final UUID me) {
        return lastContact.get(me);
    }

    /** 下线清掉：自己这条、以及别人记着的指向自己的那条。 */
    public void forget(final UUID leaving) {
        lastContact.remove(leaving);
        lastContact.values().removeIf(leaving::equals);
        spying.remove(leaving);
        toggledOff.remove(leaving);
    }

    // ------------------------------------------------------------------
    // 存盘
    // ------------------------------------------------------------------

    public void load() {
        ignored.clear();
        toggledOff.clear();
        if (!Files.isDirectory(directory)) {
            return;
        }
        final Path ignoreFile = directory.resolve("ignores.txt");
        if (Files.exists(ignoreFile)) {
            final Map<UUID, Set<UUID>> loaded = new HashMap<>();
            for (final String line : read(ignoreFile)) {
                final String[] parts = line.split("\\s+");
                if (parts.length < 2) {
                    continue;
                }
                try {
                    final UUID owner = UUID.fromString(parts[0]);
                    final UUID target = UUID.fromString(parts[1]);
                    loaded.computeIfAbsent(owner, k -> ConcurrentHashMap.newKeySet()).add(target);
                } catch (final IllegalArgumentException ignoredUuid) {
                    // 手改坏了的行跳过就行
                }
            }
            ignored.putAll(loaded);
        }
        final Path toggleFile = directory.resolve("toggles.txt");
        if (Files.exists(toggleFile)) {
            for (final String line : read(toggleFile)) {
                try {
                    toggledOff.add(UUID.fromString(line.trim()));
                } catch (final IllegalArgumentException ignoredUuid) {
                    // 同上
                }
            }
        }
    }

    public void saveIgnores() {
        final List<String> lines = new ArrayList<>();
        for (final Map.Entry<UUID, Set<UUID>> entry : ignored.entrySet()) {
            for (final UUID target : entry.getValue()) {
                lines.add(entry.getKey() + " " + target);
            }
        }
        write(directory.resolve("ignores.txt"), lines);
    }

    public void saveToggles() {
        final List<String> lines = new ArrayList<>();
        for (final UUID uuid : toggledOff) {
            lines.add(uuid.toString());
        }
        write(directory.resolve("toggles.txt"), lines);
    }

    private List<String> read(final Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            logger.warn("[vwhisper] 读 " + file.getFileName() + " 失败：" + e.getMessage());
            return Collections.emptyList();
        }
    }

    private void write(final Path file, final List<String> lines) {
        try {
            Files.createDirectories(directory);
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                for (final String line : lines) {
                    writer.write(line);
                    writer.newLine();
                }
            }
        } catch (final IOException e) {
            logger.warn("[vwhisper] 写 " + file.getFileName() + " 失败：" + e.getMessage());
        }
    }
}
