/*
 * Copyright 2026 suhong
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @author suhong
 */
package com.suhong.jiuding.cli;

import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;

/**
 * Reads/writes the CLI config file. Writes are atomic when the filesystem supports it
 * (temp file + move) and the file is restricted to the current user when the platform
 * supports POSIX permissions ("chmod 600 best effort").
 */
public class ConfigStore {

    private static final String CONFIG_PROPERTY = "tenancy.config";
    private static final String CONFIG_ENV = "TENANCY_CONFIG";

    private final Path path;

    public ConfigStore(Path path) {
        this.path = path;
    }

    /**
     * Resolution order: explicit {@code --config} value, {@code -Dtenancy.config},
     * {@code TENANCY_CONFIG} env var, then {@code <user.home>/.tenancy/config.json}.
     */
    public static Path resolvePath(String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return Paths.get(explicit);
        }
        String property = System.getProperty(CONFIG_PROPERTY);
        if (property != null && !property.isBlank()) {
            return Paths.get(property);
        }
        String env = System.getenv(CONFIG_ENV);
        if (env != null && !env.isBlank()) {
            return Paths.get(env);
        }
        return Paths.get(System.getProperty("user.home", "."), ".tenancy", "config.json");
    }

    public Path path() {
        return path;
    }

    public CliConfig load() {
        if (!Files.exists(path)) {
            return new CliConfig();
        }
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            if (raw.isBlank()) {
                return new CliConfig();
            }
            CliConfig config = Json.mapper().readValue(raw, CliConfig.class);
            return config == null ? new CliConfig() : config;
        } catch (IOException e) {
            throw new CliUsageException("读取配置文件失败: " + path + " (" + e.getMessage() + ")");
        }
    }

    public void save(CliConfig config) {
        config.updatedAt = Instant.now().toString();
        String json;
        try {
            json = Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new CliUsageException("序列化配置失败: " + e.getOriginalMessage());
        }
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            if (!moveIntoPlace(temp, json)) {
                Files.writeString(path, json, StandardCharsets.UTF_8);
                Files.deleteIfExists(temp);
            }
            restrictToOwner(path);
        } catch (IOException e) {
            throw new CliUsageException("写入配置文件失败: " + path + " (" + e.getMessage() + ")");
        }
    }

    /** Tries atomic replace, then plain replace; returns false when both moves are refused. */
    private boolean moveIntoPlace(Path temp, String json) {
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (AtomicMoveNotSupportedException e) {
            // Fall through to plain replace.
        } catch (IOException e) {
            // Fall through to plain replace.
        }
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public void delete() {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new CliUsageException("删除配置文件失败: " + path + " (" + e.getMessage() + ")");
        }
    }

    /** Best-effort "chmod 600"; Windows does not expose POSIX permissions, so this is skipped there. */
    private static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Best effort only - never fail the command because of permission restrictions.
        }
    }
}
