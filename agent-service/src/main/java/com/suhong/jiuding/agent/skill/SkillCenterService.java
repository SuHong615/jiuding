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
package com.suhong.jiuding.agent.skill;

import com.suhong.jiuding.agent.repository.SkillPackageRepository;
import com.suhong.jiuding.agent.repository.SkillReviewRepository;
import com.suhong.jiuding.agent.rule.RuleSet;
import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.error.ErrorCode;
import com.suhong.jiuding.common.security.AuthContext;
import com.suhong.jiuding.common.security.Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Skill center lifecycle service (docs/15 §2.2):
 * upload → draft → submit → review → approve/reject → enable/disable → delete.
 *
 * <p>Built-in packages ({@code skills/}) are read-only through the API; uploaded
 * packages are persisted in {@code skill_packages} and only {@code APPROVED &&
 * enabled} rows are loaded into the runtime registry.
 */
@Service
public class SkillCenterService {

    private static final Logger log = LoggerFactory.getLogger(SkillCenterService.class);

    public static final int MAX_ZIP_BYTES = 1024 * 1024;
    private static final int MAX_ENTRY_BYTES = 1024 * 1024;
    private static final int MAX_TOTAL_UNCOMPRESSED = 2 * 1024 * 1024;
    private static final int MAX_ENTRIES = 32;
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z][a-z0-9-]{2,63}$");
    private static final Pattern VERSION_PATTERN = Pattern.compile("^[0-9][0-9A-Za-z.+_-]{0,31}$");

    private final SkillPackageRepository packageRepository;
    private final SkillReviewRepository reviewRepository;
    private final SkillRegistry registry;
    private final com.suhong.jiuding.agent.rule.RuleEngine ruleEngine;

    public SkillCenterService(SkillPackageRepository packageRepository,
                              SkillReviewRepository reviewRepository, SkillRegistry registry,
                              com.suhong.jiuding.agent.rule.RuleEngine ruleEngine) {
        this.packageRepository = packageRepository;
        this.reviewRepository = reviewRepository;
        this.registry = registry;
        this.ruleEngine = ruleEngine;
    }

    // ------------------------------------------------------------------ queries

    /** List latest package per id, optionally filtered by status/source/enabled. */
    public List<SkillView> list(String status, String source, String enabled) {
        String normalizedStatus = normalizeFilter(SkillPackage.normalizeStatus(status), status);
        String normalizedSource = normalizeFilter(SkillPackage.normalizeSource(source), source);
        Boolean enabledFilter = parseBoolean(enabled);
        List<SkillPackage> packages = packageRepository.list(normalizedStatus, normalizedSource, enabledFilter);
        List<SkillView> views = new ArrayList<>();
        for (SkillPackage pkg : packages) {
            views.add(SkillView.of(pkg));
        }
        if (views.isEmpty() && normalizedStatus == null && normalizedSource == null && enabledFilter == null
                && registry.count() > 0) {
            // DB mirror not populated yet (e.g. startup race): serve the loaded registry
            for (SkillRegistry.LoadedSkill loaded : registry.loadedSkills()) {
                views.add(SkillView.of(loaded));
            }
        }
        return views;
    }

    public SkillDetail detail(String id) {
        Optional<SkillRegistry.LoadedSkill> loaded = registry.findLoaded(id);
        if (loaded.isPresent()) {
            return SkillDetail.of(loaded.get(), registry.loadedAt().toString());
        }
        SkillPackage pkg = packageRepository.findLatest(id)
                .orElseThrow(() -> new BizException(ErrorCode.SKILL_NOT_FOUND, "skill not found: " + id));
        return SkillDetail.of(pkg, registry.loadedAt().toString());
    }

    public List<SkillReview> reviews(String id) {
        boolean exists = packageRepository.findLatest(id).isPresent() || registry.find(id).isPresent();
        if (!exists) {
            throw new BizException(ErrorCode.SKILL_NOT_FOUND, "skill not found: " + id);
        }
        return reviewRepository.findBySkillId(id);
    }

    // --------------------------------------------------------------- versions

    /** All versions of one skill, newest first (built-ins expose a single entry). */
    public List<SkillVersionView> versions(String id) {
        List<SkillPackage> rows = new ArrayList<>(packageRepository.listVersions(id));
        if (rows.isEmpty()) {
            SkillRegistry.LoadedSkill loaded = registry.findLoaded(id)
                    .orElseThrow(() -> new BizException(ErrorCode.SKILL_NOT_FOUND, "skill not found: " + id));
            return List.of(SkillVersionView.of(loaded, true));
        }
        rows.sort((left, right) -> VersionOrder.DESCENDING.compare(left.version(), right.version()));
        String current = currentVersion(rows);
        List<SkillVersionView> views = new ArrayList<>(rows.size());
        for (SkillPackage pkg : rows) {
            views.add(SkillVersionView.of(pkg, pkg.version().equals(current)));
        }
        return views;
    }

    /** Detail of one exact version; 404 when the version does not exist. */
    public SkillDetail versionDetail(String id, String version) {
        if (version == null || version.isBlank()) {
            throw BizException.invalid("version 必填");
        }
        String normalized = version.trim();
        Optional<SkillPackage> pkg = packageRepository.find(id, normalized);
        if (pkg.isPresent()) {
            return SkillDetail.of(pkg.get(), registry.loadedAt().toString());
        }
        Optional<SkillRegistry.LoadedSkill> loaded = registry.findLoaded(id);
        if (loaded.isPresent() && normalized.equals(loaded.get().definition().manifest().version())) {
            return SkillDetail.of(loaded.get(), registry.loadedAt().toString());
        }
        throw new BizException(ErrorCode.SKILL_NOT_FOUND,
                "skill version not found: " + id + "@" + normalized);
    }

    /**
     * Current version: newest {@code APPROVED && enabled} row, else the newest row.
     * {@code rows} must already be sorted by {@link VersionOrder#DESCENDING}.
     */
    private static String currentVersion(List<SkillPackage> rows) {
        for (SkillPackage pkg : rows) {
            if (pkg.enabled() && SkillPackage.STATUS_APPROVED.equals(pkg.status())) {
                return pkg.version();
            }
        }
        return rows.isEmpty() ? null : rows.get(0).version();
    }

    // ------------------------------------------------------------------ debug

    /**
     * Run a skill's rules in memory and return an explained trace. Nothing is
     * persisted (no {@code rule_hits}, no audit), so this is safe while editing.
     */
    public SkillDebugResult debug(String id, Map<String, Object> input, String requestedVersion) {
        long startedAt = System.currentTimeMillis();
        List<String> notes = new ArrayList<>();
        String resolvedVersion = null;
        RuleSet rules = null;
        Optional<SkillRegistry.LoadedSkill> loaded = registry.findLoaded(id);

        if (requestedVersion != null && !requestedVersion.isBlank()) {
            String version = requestedVersion.trim();
            SkillPackage pkg = packageRepository.find(id, version)
                    .orElseThrow(() -> new BizException(ErrorCode.SKILL_NOT_FOUND,
                            "skill version not found: " + id + "@" + version));
            requireDebugPermission(pkg);
            resolvedVersion = version;
            rules = parseRulesForDebug(pkg, notes);
        } else if (loaded.isPresent()) {
            SkillRegistry.LoadedSkill current = loaded.get();
            if (SkillPackage.SOURCE_UPLOADED.equals(current.source())) {
                requireManager();
            }
            resolvedVersion = current.definition().manifest().version();
            rules = current.definition().rules();
        } else {
            SkillPackage pkg = highestVersionPackage(id);
            requireDebugPermission(pkg);
            resolvedVersion = pkg.version();
            rules = parseRulesForDebug(pkg, notes);
        }

        String decision = "NONE";
        int score = 0;
        List<Action> actions = List.of();
        List<SkillDebugResult.MatchedRule> matchedRules = new ArrayList<>();
        if (rules == null) {
            notes.add("规则不可用，未执行规则引擎");
        } else if (rules.rules().isEmpty()) {
            notes.add("该技能未配置可执行规则（rules.yaml 为空），仅返回空结果");
        } else {
            Map<String, Object> context = input == null ? Map.of() : input;
            SkillRunResult result = ruleEngine.run(rules, context, null,
                    "debug-" + java.util.UUID.randomUUID(), "debug");
            decision = result.decision();
            score = result.score();
            actions = result.actions();

            Map<String, TraceItem> traceByRule = new LinkedHashMap<>();
            for (TraceItem item : result.trace()) {
                traceByRule.put(item.ruleId(), item);
            }
            int matchedCount = 0;
            for (com.suhong.jiuding.agent.rule.Rule rule : rules.rules()) {
                TraceItem trace = traceByRule.get(rule.id());
                boolean matched = trace != null && trace.matched();
                if (matched) {
                    matchedCount++;
                }
                List<Action> ruleActions = new ArrayList<>();
                for (Action action : result.actions()) {
                    if (rule.id().equals(action.ruleId())) {
                        ruleActions.add(action);
                    }
                }
                matchedRules.add(new SkillDebugResult.MatchedRule(rule.id(), rule.description(),
                        matched, ruleActions));
                if (trace != null && trace.reason() != null && trace.reason().startsWith("error:")) {
                    notes.add("规则 " + rule.id() + " 执行失败：" + trace.reason());
                }
            }
            notes.add("共 " + rules.rules().size() + " 条规则，命中 " + matchedCount + " 条");
        }
        notes.add("调试执行仅内存运行（版本 " + resolvedVersion + "），不写入 rule_hits / 审计");
        return new SkillDebugResult(resolvedVersion, decision, score, actions, matchedRules,
                java.time.Instant.now().toString(),
                System.currentTimeMillis() - startedAt, notes);
    }

    private RuleSet parseRulesForDebug(SkillPackage pkg, List<String> notes) {
        try {
            return SkillLoader.fromPackage(pkg).rules();
        } catch (Exception e) {
            notes.add("规则解析失败：" + e.getMessage());
            return null;
        }
    }

    /** Newest version by semantic order (deterministic regardless of row timestamps). */
    private SkillPackage highestVersionPackage(String id) {
        List<SkillPackage> rows = new ArrayList<>(packageRepository.listVersions(id));
        if (rows.isEmpty()) {
            throw new BizException(ErrorCode.SKILL_NOT_FOUND, "skill not found: " + id);
        }
        rows.sort((left, right) -> VersionOrder.DESCENDING.compare(left.version(), right.version()));
        return rows.get(0);
    }

    private void requireDebugPermission(SkillPackage pkg) {
        if (SkillPackage.SOURCE_UPLOADED.equals(pkg.source())) {
            requireManager();
        }
    }

    // ----------------------------------------------------------------- mutation

    /** Upload a zip package (SKILL.md + optional rules.yaml). */
    public SkillView upload(MultipartFile file, Boolean submitForReview) {
        Principal principal = requireManager();
        if (file == null || file.isEmpty()) {
            throw BizException.invalid("请上传技能包 zip 文件");
        }
        if (file.getSize() > MAX_ZIP_BYTES) {
            throw BizException.invalid("技能包不能超过 1MB");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw BizException.invalid("读取上传文件失败: " + e.getMessage());
        }
        ZipParts parts = readZip(bytes);
        SkillLoader.ParsedMarkdown markdown = SkillLoader.parseMarkdown(parts.skillMd());
        SkillManifest manifest = SkillLoader.parseManifest(markdown.frontmatter());
        validateManifest(manifest);
        RuleSet rules = SkillLoader.parseRuleSet(parts.rulesYaml(), manifest.id(), manifest.version());

        if (packageRepository.list(null, SkillPackage.SOURCE_BUILTIN, null).stream()
                .anyMatch(pkg -> pkg.id().equals(manifest.id()))) {
            throw BizException.conflict("技能 id 与内置技能冲突: " + manifest.id());
        }
        if (packageRepository.find(manifest.id(), manifest.version()).isPresent()) {
            throw BizException.conflict("技能版本已存在: " + manifest.id() + "@" + manifest.version());
        }

        boolean submit = Boolean.TRUE.equals(submitForReview);
        String status = submit ? SkillPackage.STATUS_PENDING_REVIEW : SkillPackage.STATUS_DRAFT;
        String actor = actor(principal);
        LocalDateTime now = SkillPackageRepository.nowUtc();
        SkillPackage pkg = new SkillPackage(manifest.id(), manifest.version(),
                SkillPackage.SOURCE_UPLOADED, status, SkillLoader.manifestMap(manifest),
                markdown.instructions(), parts.rulesYaml(), null, null, null, actor,
                now.toInstant(java.time.ZoneOffset.UTC).toString(),
                now.toInstant(java.time.ZoneOffset.UTC).toString(), false);
        packageRepository.insert(pkg);
        reviewRepository.insert(manifest.id(), manifest.version(), SkillReview.ACTION_UPLOAD, actor,
                "上传技能包（规则 " + rules.rules().size() + " 条）");
        if (submit) {
            reviewRepository.insert(manifest.id(), manifest.version(), SkillReview.ACTION_SUBMIT, actor,
                    "上传后直接提交审核");
        }
        log.info("skill uploaded: {}@{} status={} actor={}", manifest.id(), manifest.version(), status, actor);
        return SkillView.of(pkg);
    }

    /** Create/update a DRAFT (or edit a REJECTED package) under a new version. */
    public SkillView update(String id, String version, String instructions, String rulesYaml, String description) {
        Principal principal = requireManager();
        requireEditable(id);
        String newVersion = version == null ? "" : version.trim();
        if (newVersion.isBlank()) {
            throw BizException.invalid("version 必填");
        }
        if (!VERSION_PATTERN.matcher(newVersion).matches()) {
            throw BizException.invalid("version 格式不合法: " + newVersion);
        }
        SkillPackage latest = latestUploaded(id);
        SkillPackage target = packageRepository.find(id, newVersion).orElse(null);
        if (target != null) {
            if (target.builtin()) {
                throw BizException.conflict("内置技能只读");
            }
            if (!target.editable()) {
                throw BizException.conflict("当前状态不可编辑: " + target.status());
            }
        } else if (!latest.editable()) {
            throw BizException.conflict("最新版本状态不可编辑: " + latest.status());
        }

        SkillPackage base = target != null ? target : latest;
        String newInstructions = instructions != null ? instructions : base.instructions();
        String newRulesYaml = rulesYaml != null ? rulesYaml : base.rulesYaml();
        String newDescription = description != null ? description : base.toManifest().description();

        Map<String, Object> manifest = new LinkedHashMap<>(base.manifest());
        manifest.put("id", id);
        manifest.put("version", newVersion);
        manifest.put("description", newDescription);
        SkillManifest parsedManifest = SkillManifest.from(manifest);
        RuleSet rules = SkillLoader.parseRuleSet(newRulesYaml, id, newVersion);

        String actor = actor(principal);
        LocalDateTime now = SkillPackageRepository.nowUtc();
        String nowIso = now.toInstant(java.time.ZoneOffset.UTC).toString();
        String manifestJson = toJson(SkillLoader.manifestMap(parsedManifest));

        if (target != null) {
            packageRepository.resetReview(id, target.version(), SkillPackage.STATUS_DRAFT,
                    manifestJson, newInstructions, newRulesYaml, now);
        } else {
            packageRepository.insert(new SkillPackage(id, newVersion, SkillPackage.SOURCE_UPLOADED,
                    SkillPackage.STATUS_DRAFT, SkillLoader.manifestMap(parsedManifest),
                    newInstructions, newRulesYaml, null, null, null, actor, nowIso, nowIso, false));
        }
        reviewRepository.insert(id, newVersion, SkillReview.ACTION_UPDATE, actor,
                "更新草稿（规则 " + rules.rules().size() + " 条）");
        registry.reload();
        return packageRepository.find(id, newVersion).map(SkillView::of)
                .orElseThrow(() -> BizException.notFound("skill package not found after update"));
    }

    /** Submit a DRAFT/REJECTED package for platform review. */
    public SkillView submitReview(String id) {
        Principal principal = requireManager();
        SkillPackage latest = latestUploaded(id);
        if (!SkillPackage.STATUS_DRAFT.equals(latest.status())
                && !SkillPackage.STATUS_REJECTED.equals(latest.status())) {
            throw BizException.conflict("当前状态不可提交审核: " + latest.status());
        }
        LocalDateTime now = SkillPackageRepository.nowUtc();
        packageRepository.updateStatus(id, latest.version(), SkillPackage.STATUS_PENDING_REVIEW,
                false, null, null, null, now);
        reviewRepository.insert(id, latest.version(), SkillReview.ACTION_SUBMIT,
                actor(principal), "提交审核");
        return packageRepository.find(id, latest.version()).map(SkillView::of)
                .orElseThrow(() -> BizException.notFound("skill package not found: " + id));
    }

    /** Platform-admin review: APPROVE enables + hot-reloads; REJECT keeps it offline. */
    public SkillView review(String id, String decision, String comment) {
        Principal principal = requireAdmin();
        SkillPackage latest = latestUploaded(id);
        if (!SkillPackage.STATUS_PENDING_REVIEW.equals(latest.status())) {
            throw BizException.conflict("当前状态不可审核: " + latest.status());
        }
        String normalized = decision == null ? "" : decision.trim().toUpperCase(java.util.Locale.ROOT);
        boolean approve;
        if ("APPROVE".equals(normalized)) {
            approve = true;
        } else if ("REJECT".equals(normalized)) {
            approve = false;
        } else {
            throw BizException.invalid("decision 必须为 APPROVE 或 REJECT");
        }
        String actor = actor(principal);
        if (!approve && (comment == null || comment.isBlank())) {
            throw BizException.invalid("驳回必须填写审核意见");
        }
        LocalDateTime now = SkillPackageRepository.nowUtc();
        String nowIso = now.toInstant(java.time.ZoneOffset.UTC).toString();
        if (approve) {
            packageRepository.updateStatus(id, latest.version(), SkillPackage.STATUS_APPROVED,
                    true, comment, actor, nowIso, now);
            packageRepository.disableOtherUploadedVersions(id, latest.version(), now);
            reviewRepository.insert(id, latest.version(), SkillReview.ACTION_APPROVE, actor, comment);
            registry.reload();
            log.info("skill approved and hot-loaded: {}@{} by {}", id, latest.version(), actor);
        } else {
            packageRepository.updateStatus(id, latest.version(), SkillPackage.STATUS_REJECTED,
                    false, comment, actor, nowIso, now);
            reviewRepository.insert(id, latest.version(), SkillReview.ACTION_REJECT, actor, comment);
            registry.reload();
        }
        return packageRepository.find(id, latest.version()).map(SkillView::of)
                .orElseThrow(() -> BizException.notFound("skill package not found: " + id));
    }

    public SkillView enable(String id) {
        return toggleEnabled(id, true);
    }

    public SkillView disable(String id) {
        return toggleEnabled(id, false);
    }

    private SkillView toggleEnabled(String id, boolean enabled) {
        Principal principal = requireManager();
        SkillPackage latest = latestUploaded(id);
        if (enabled && !SkillPackage.STATUS_APPROVED.equals(latest.status())) {
            throw BizException.conflict("只有审核通过的技能才能启用，当前状态: " + latest.status());
        }
        LocalDateTime now = SkillPackageRepository.nowUtc();
        packageRepository.setEnabled(id, latest.version(), enabled, now);
        if (enabled) {
            packageRepository.disableOtherUploadedVersions(id, latest.version(), now);
        }
        reviewRepository.insert(id, latest.version(),
                enabled ? SkillReview.ACTION_ENABLE : SkillReview.ACTION_DISABLE,
                actor(principal), enabled ? "启用技能" : "停用技能");
        registry.reload();
        return packageRepository.find(id, latest.version()).map(SkillView::of)
                .orElseThrow(() -> BizException.notFound("skill package not found: " + id));
    }

    /** Logical delete: uploaded packages only, moved to DISABLED and unloaded. */
    public SkillView delete(String id) {
        Principal principal = requireManager();
        SkillPackage latest = latestUploaded(id);
        LocalDateTime now = SkillPackageRepository.nowUtc();
        packageRepository.updateStatus(id, latest.version(), SkillPackage.STATUS_DISABLED, false,
                latest.reviewComment(), latest.reviewedBy(), latest.reviewedAt(), now);
        reviewRepository.insert(id, latest.version(), SkillReview.ACTION_DISABLE, actor(principal),
                "删除技能包（逻辑删除，状态 DISABLED）");
        registry.reload();
        return packageRepository.find(id, latest.version()).map(SkillView::of)
                .orElseThrow(() -> BizException.notFound("skill package not found: " + id));
    }

    // ---------------------------------------------------------------- template

    /** Skill package template zip: SKILL.md + rules.yaml + README. */
    public byte[] templateZip() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", """
                ---
                id: my-custom-skill
                name: 我的自定义技能
                version: 1.0.0
                type: RISK
                description: 一句话说明这个技能解决什么问题
                triggers: [tenant.submitted]
                tools: [tenant.get]
                tags: [custom]
                ---

                # 角色

                你是多租户 SaaS 平台的【技能名称】Agent。描述你的职责边界。

                # 输入

                - `tenantId`、`tenantCode`、`plan`、`region`、`quotaSeats`
                - `contactEmail`、`attributes`（如 `industry`）

                # 工作流

                1. 读取租户画像（规则引擎确定性执行，无需 LLM 推断）。
                2. 依据 `rules.yaml` 命中情况给出结论。
                3. 输出 JSON：{"decision":"NEEDS_REVIEW","reasons":[]}

                # 约束

                - 只读，不得修改租户数据。
                - 只输出 JSON，不要 markdown 代码块。
                """);
        files.put("rules.yaml", """
                skillId: my-custom-skill
                version: 1.0.0
                initialScore: 0
                rules:
                  - id: R-CUSTOM-001
                    description: ENTERPRISE 租户需要人工复核
                    severity: MEDIUM
                    when: { field: plan, op: eq, value: ENTERPRISE }
                    then:
                      - { action: addScore, value: 20 }
                      - { action: addFinding, message: ENTERPRISE 租户需要人工复核, severity: MEDIUM }
                decision:
                  autoApproveMaxScore: 20
                  rejectMinScore: 80
                  riskLevels:
                    - { level: LOW, maxScore: 20 }
                    - { level: MEDIUM, maxScore: 60 }
                    - { level: HIGH, maxScore: 100 }
                """);
        files.put("README.md", """
                # 技能包模板

                1. 修改 `SKILL.md` frontmatter：`id`（小写字母/数字/连字符，3-64 位）、`name`、`version`、`type`
                   （INTAKE/RISK/APPROVAL/PROVISIONING/RATING）、`description` 必填。
                2. 按需修改 `rules.yaml`：支持 all/any/not 组合与 10 个运算符、7 种动作。
                3. 将 `SKILL.md`（可选 `rules.yaml`）打包为 zip，保持文件在 zip 根目录或同一级目录下。
                4. 在「技能中心」上传：zip ≤ 1MB；与内置技能 id 冲突会被拒绝（409）。
                5. 上传后为草稿（或直接提交审核）→ 提交审核 → 平台管理员审核通过 → 自动热加载生效。

                详细规范见 `docs/04-skill-spec.md` 与 `docs/15-v5-skill-mcp-onboarding-rbac.md`。
                """);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "生成技能模板失败: " + e.getMessage(), e);
        }
    }

    // ----------------------------------------------------------------- helpers

    private SkillPackage latestUploaded(String id) {
        SkillPackage latest = packageRepository.findLatest(id)
                .orElseThrow(() -> new BizException(ErrorCode.SKILL_NOT_FOUND, "skill not found: " + id));
        if (latest.builtin()) {
            throw BizException.conflict("内置技能只读: " + id);
        }
        return latest;
    }

    private void requireEditable(String id) {
        SkillPackage latest = packageRepository.findLatest(id)
                .orElseThrow(() -> new BizException(ErrorCode.SKILL_NOT_FOUND, "skill not found: " + id));
        if (latest.builtin()) {
            throw BizException.conflict("内置技能只读: " + id);
        }
        if (!latest.editable()) {
            throw BizException.conflict("当前状态不可编辑: " + latest.status());
        }
    }

    private void validateManifest(SkillManifest manifest) {
        if (!ID_PATTERN.matcher(manifest.id()).matches()) {
            throw BizException.invalid("技能 id 不合法（小写字母开头，3-64 位小写字母/数字/连字符）: " + manifest.id());
        }
        if (!VERSION_PATTERN.matcher(manifest.version()).matches()) {
            throw BizException.invalid("技能 version 不合法: " + manifest.version());
        }
    }

    private String normalizeFilter(String normalized, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (normalized == null) {
            throw BizException.invalid("不支持的过滤值: " + raw);
        }
        return normalized;
    }

    private Boolean parseBoolean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if ("true".equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        throw BizException.invalid("enabled 必须为 true 或 false");
    }

    private Principal requireManager() {
        Principal principal = AuthContext.require();
        if (principal.isPlatformAdmin() || principal.hasRole("TENANT_ADMIN")) {
            return principal;
        }
        throw BizException.forbidden("需要平台管理员或租户管理员权限");
    }

    private Principal requireAdmin() {
        Principal principal = AuthContext.require();
        if (principal.isPlatformAdmin()) {
            return principal;
        }
        throw BizException.forbidden("仅平台管理员可审核技能包");
    }

    private static String actor(Principal principal) {
        return principal.username() != null && !principal.username().isBlank()
                ? principal.username() : principal.userId();
    }

    private String toJson(Map<String, Object> value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw BizException.invalid("manifest 序列化失败: " + e.getMessage());
        }
    }

    /** Read the zip into memory with hard limits (size / entries / traversal). */
    private ZipParts readZip(byte[] bytes) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0;
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (++count > MAX_ENTRIES) {
                    throw BizException.invalid("zip 条目过多（上限 " + MAX_ENTRIES + "）");
                }
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("..") || name.contains(":")) {
                    throw BizException.invalid("zip 中包含非法路径: " + name);
                }
                byte[] data = readLimited(zip);
                total += data.length;
                if (total > MAX_TOTAL_UNCOMPRESSED) {
                    throw BizException.invalid("zip 解压后总大小超过 2MB");
                }
                entries.put(name, data);
            }
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            throw BizException.invalid("无法解析技能包 zip: " + e.getMessage());
        }
        if (entries.isEmpty()) {
            throw BizException.invalid("技能包 zip 为空");
        }
        String skillMdPath = null;
        for (String name : entries.keySet()) {
            if (name.equals("SKILL.md") || name.endsWith("/SKILL.md")) {
                int segments = name.split("/").length;
                if (segments > 2) {
                    throw BizException.invalid("SKILL.md 必须位于 zip 根目录或一级目录");
                }
                if (skillMdPath != null) {
                    throw BizException.invalid("zip 中存在多个 SKILL.md");
                }
                skillMdPath = name;
            }
        }
        if (skillMdPath == null) {
            throw BizException.invalid("技能包 zip 缺少 SKILL.md");
        }
        String directory = skillMdPath.equals("SKILL.md") ? ""
                : skillMdPath.substring(0, skillMdPath.length() - "SKILL.md".length());
        byte[] rules = entries.get(directory + "rules.yaml");
        return new ZipParts(new String(entries.get(skillMdPath), StandardCharsets.UTF_8),
                rules == null ? null : new String(rules, StandardCharsets.UTF_8));
    }

    private static byte[] readLimited(ZipInputStream zip) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = zip.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            if (out.size() > MAX_ENTRY_BYTES) {
                throw BizException.invalid("zip 中单个文件超过 1MB");
            }
        }
        return out.toByteArray();
    }

    /** Parsed zip payload. */
    private record ZipParts(String skillMd, String rulesYaml) {
    }

    /** Exposed for tests/diagnostics: supported package sources. */
    public static Set<String> supportedSources() {
        return Set.copyOf(SkillPackage.SOURCES);
    }
}
