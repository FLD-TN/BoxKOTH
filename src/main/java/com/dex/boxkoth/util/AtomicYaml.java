package com.dex.boxkoth.util;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Crash-safe YAML writes: write to {@code <name>.tmp}, then atomically replace the target (§16.5). */
public final class AtomicYaml {

    private AtomicYaml() {
    }

    public static void save(YamlConfiguration yaml, File target) throws IOException {
        Path path = target.toPath();
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(target.getName() + ".tmp");
        Files.writeString(tmp, yaml.saveToString(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
