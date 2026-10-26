package com.dex.boxkoth.config;

import com.dex.boxkoth.util.ColorFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * messages.yml with {@code &} codes plus every hex / gradient syntax of {@link ColorFormat}. Values are substituted in a single pass over the
 * template, so a clan or player name containing "{x}" is never re-expanded. PlaceholderAPI is never applied here.
 */
public final class Messages {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();
    private static final Pattern TOKEN = Pattern.compile("\\{([a-z0-9_]+)}");

    private final YamlConfiguration yaml;

    private Messages(YamlConfiguration yaml) {
        this.yaml = yaml;
    }

    /** @param yaml user file; its defaults must be set to the bundled messages.yml. */
    public static Messages of(YamlConfiguration yaml) {
        return new Messages(yaml);
    }

    public static Component colorize(String text) {
        return LEGACY.deserialize(ColorFormat.normalize(text == null ? "" : text.replace('§', '&')));
    }

    /** Substitutes {key} tokens from key/value pairs in one pass. */
    public static String format(String template, String... kv) {
        if (template == null || template.isEmpty() || kv.length == 0) {
            return template == null ? "" : template;
        }
        Matcher m = TOKEN.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 32);
        while (m.find()) {
            String value = lookup(m.group(1), kv);
            m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String lookup(String key, String[] kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i].equals(key)) {
                return kv[i + 1] == null ? "" : kv[i + 1];
            }
        }
        return null;
    }

    /** A key missing from the user's file (older messages.yml) falls back to the bundled text; '' disables it. */
    public String raw(String key) {
        String value = yaml.getString(key);
        return value == null ? "" : value;
    }

    /** Formatted text with the prefix, or "" when the message is empty (disabled). */
    public String text(String key, String... kv) {
        String template = raw(key);
        if (template.isEmpty()) return "";
        return raw("prefix") + format(template, kv);
    }

    public void send(CommandSender to, String key, String... kv) {
        String text = text(key, kv);
        if (!text.isEmpty()) {
            to.sendMessage(colorize(text));
        }
    }

    /** Sends every line of a list message, without prefix. */
    public void sendList(CommandSender to, String key, String... kv) {
        for (String line : lines(key, kv)) {
            to.sendMessage(colorize(line));
        }
    }

    public List<String> lines(String key, String... kv) {
        List<String> result = new ArrayList<>();
        for (String line : yaml.getStringList(key)) {
            result.add(format(line, kv));
        }
        return result;
    }

    public void broadcast(String key, String... kv) {
        String text = text(key, kv);
        if (!text.isEmpty()) {
            Bukkit.getServer().broadcast(colorize(text));
        }
    }

    public void broadcastRaw(String text) {
        if (!text.isEmpty()) {
            Bukkit.getServer().broadcast(colorize(text));
        }
    }

    /** Sends to online players with the given permission and to the console. */
    public void staff(String permission, String key, String... kv) {
        String text = text(key, kv);
        if (text.isEmpty()) return;
        Component c = colorize(text);
        Bukkit.getConsoleSender().sendMessage(c);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission(permission)) {
                p.sendMessage(c);
            }
        }
    }

    public void actionBar(Player to, String key, String... kv) {
        String template = raw(key);
        if (!template.isEmpty()) {
            to.sendActionBar(colorize(format(template, kv)));
        }
    }
}
