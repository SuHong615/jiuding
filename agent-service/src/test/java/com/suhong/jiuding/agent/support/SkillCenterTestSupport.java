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
package com.suhong.jiuding.agent.support;

import com.suhong.jiuding.common.security.JwtService;
import com.suhong.jiuding.common.security.Principal;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Shared helpers for the v5 skill-center / agent-readability tests. */
public final class SkillCenterTestSupport {

    private SkillCenterTestSupport() {
    }

    public static String adminToken(JwtService jwtService) {
        return jwtService.issue(new Principal("u-admin", "admin", null,
                Set.of("PLATFORM_ADMIN"), "JWT"));
    }

    public static String tenantAdminToken(JwtService jwtService) {
        return jwtService.issue(new Principal("u-op", "operator", "t-acme",
                Set.of("TENANT_ADMIN"), "JWT"));
    }

    public static String approverToken(JwtService jwtService) {
        return jwtService.issue(new Principal("u-approver", "approver", null,
                Set.of("APPROVER"), "JWT"));
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }

    /** Unique, spec-compliant skill id: lowercase letter start, 3-64 chars. */
    public static String uniqueSkillId() {
        return "ut" + UUID.randomUUID().toString().replace("-", "").substring(0, 10)
                .toLowerCase(Locale.ROOT);
    }

    /** Minimal valid skill package (SKILL.md + rules.yaml). */
    public static Map<String, String> skillFiles(String id, String version) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", """
                ---
                id: %s
                name: 上传技能 %s
                version: %s
                type: RISK
                description: 测试用上传技能
                triggers: [tenant.submitted]
                tools: [tenant.get]
                tags: [test]
                ---

                # 角色
                你是测试技能。
                """.formatted(id, id, version));
        files.put("rules.yaml", """
                skillId: %s
                version: %s
                initialScore: 0
                rules:
                  - id: R-UP-001
                    description: ENTERPRISE 需要复核
                    severity: MEDIUM
                    when: { field: plan, op: eq, value: ENTERPRISE }
                    then:
                      - { action: addScore, value: 50 }
                      - { action: addFinding, message: ENTERPRISE 需要人工复核, severity: MEDIUM }
                decision:
                  autoApproveMaxScore: 20
                  rejectMinScore: 80
                  riskLevels:
                    - { level: LOW, maxScore: 20 }
                    - { level: MEDIUM, maxScore: 60 }
                    - { level: HIGH, maxScore: 100 }
                """.formatted(id, version));
        return files;
    }

    public static byte[] zip(Map<String, String> files) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.finish();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("failed to build test zip", e);
        }
    }

    public static MockMultipartFile zipPart(Map<String, String> files) {
        return new MockMultipartFile("file", "skill.zip", "application/zip", zip(files));
    }

    public static List<String> entries(byte[] zipBytes) {
        List<String> names = new java.util.ArrayList<>();
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    names.add(entry.getName());
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to read zip", e);
        }
        return names;
    }
}
