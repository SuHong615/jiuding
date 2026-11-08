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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Config file round-trip and safe defaults. */
class ConfigStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void loadMissingFileReturnsEmptyConfig() {
        ConfigStore store = new ConfigStore(tempDir.resolve("nested").resolve("config.json"));
        CliConfig config = store.load();
        assertNull(config.token);
        assertNull(config.baseUrl);
    }

    @Test
    void saveAndLoadRoundTrip() {
        Path file = tempDir.resolve("config.json");
        ConfigStore store = new ConfigStore(file);
        CliConfig config = new CliConfig();
        config.baseUrl = "http://localhost:8080";
        config.token = "jwt-token-value";
        config.tenantId = "t-acme";
        config.apiKey = "dev-mcp-key";
        config.username = "admin";

        store.save(config);

        assertTrue(Files.exists(file), "config file must be created");
        assertNotNull(config.updatedAt);
        CliConfig loaded = store.load();
        assertEquals("http://localhost:8080", loaded.baseUrl);
        assertEquals("jwt-token-value", loaded.token);
        assertEquals("t-acme", loaded.tenantId);
        assertEquals("dev-mcp-key", loaded.apiKey);
        assertEquals("admin", loaded.username);
    }

    @Test
    void saveOverwritesPreviousContentAtomically() {
        Path file = tempDir.resolve("config.json");
        ConfigStore store = new ConfigStore(file);
        CliConfig first = new CliConfig();
        first.token = "first";
        store.save(first);
        CliConfig second = new CliConfig();
        second.token = "second";
        store.save(second);

        assertEquals("second", store.load().token);
        assertEquals(1, tempDir.toFile().listFiles().length, "temporary file must not be left behind");
    }

    @Test
    void removeTokenKeepsOtherSettings() {
        Path file = tempDir.resolve("config.json");
        ConfigStore store = new ConfigStore(file);
        CliConfig config = new CliConfig();
        config.baseUrl = "http://example.test:8080";
        config.token = "to-be-removed";
        store.save(config);

        CliConfig cleared = store.load();
        cleared.token = null;
        store.save(cleared);

        assertNull(store.load().token);
        assertEquals("http://example.test:8080", store.load().baseUrl);
    }

    @Test
    void savedFileIsPlainUtf8Json() throws Exception {
        Path file = tempDir.resolve("config.json");
        ConfigStore store = new ConfigStore(file);
        CliConfig config = new CliConfig();
        config.username = "管理员";
        store.save(config);

        String raw = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(raw.contains("管理员"));
        assertFalse(raw.startsWith("\uFEFF"), "config file must not start with a BOM");
    }

    @Test
    void explicitPathWinsOverEnvironment() {
        Path explicit = ConfigStore.resolvePath(tempDir.resolve("explicit.json").toString());
        assertEquals(explicit, ConfigStore.resolvePath(explicit.toString()));
    }
}
