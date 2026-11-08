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
package com.suhong.jiuding.agent.api;

import com.suhong.jiuding.agent.skill.SkillCenterService;
import com.suhong.jiuding.agent.skill.SkillDebugResult;
import com.suhong.jiuding.agent.skill.SkillDetail;
import com.suhong.jiuding.agent.skill.SkillReview;
import com.suhong.jiuding.agent.skill.SkillVersionView;
import com.suhong.jiuding.agent.skill.SkillView;
import com.suhong.jiuding.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Skill center: browse + upload / update / review / enable / disable / delete /
 * template download (docs/15 §2.2).
 */
@RestController
@RequestMapping("/api/skills")
public class SkillCenterController {

    private final SkillCenterService skillCenterService;

    public SkillCenterController(SkillCenterService skillCenterService) {
        this.skillCenterService = skillCenterService;
    }

    @GetMapping
    public ApiResponse<List<SkillView>> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "enabled", required = false) String enabled) {
        return ApiResponse.ok(skillCenterService.list(status, source, enabled));
    }

    @GetMapping("/template")
    public ResponseEntity<ByteArrayResource> template() {
        byte[] bytes = skillCenterService.templateZip();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"skill-template.zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(bytes.length)
                .body(new ByteArrayResource(bytes));
    }

    @GetMapping("/{id}")
    public ApiResponse<SkillDetail> detail(@PathVariable("id") String id) {
        return ApiResponse.ok(skillCenterService.detail(id));
    }

    @GetMapping("/{id}/reviews")
    public ApiResponse<List<SkillReview>> reviews(@PathVariable("id") String id) {
        return ApiResponse.ok(skillCenterService.reviews(id));
    }

    @GetMapping("/{id}/versions")
    public ApiResponse<List<SkillVersionView>> versions(@PathVariable("id") String id) {
        return ApiResponse.ok(skillCenterService.versions(id));
    }

    @GetMapping("/{id}/versions/{version}")
    public ApiResponse<SkillDetail> versionDetail(@PathVariable("id") String id,
                                                  @PathVariable("version") String version) {
        return ApiResponse.ok(skillCenterService.versionDetail(id, version));
    }

    @PostMapping("/{id}/debug")
    public ApiResponse<SkillDebugResult> debug(@PathVariable("id") String id,
                                               @RequestBody(required = false) SkillDebugRequest request) {
        Map<String, Object> input = request == null ? Map.of()
                : (request.input() == null ? Map.of() : request.input());
        String version = request == null ? null : request.version();
        return ApiResponse.ok(skillCenterService.debug(id, input, version));
    }

    @PostMapping("/upload")
    public ApiResponse<SkillView> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "submitForReview", required = false) Boolean submitForReview) {
        return ApiResponse.ok(skillCenterService.upload(file, submitForReview));
    }

    @PutMapping("/{id}")
    public ApiResponse<SkillView> update(@PathVariable("id") String id,
                                         @Valid @RequestBody SkillUpdateRequest request) {
        return ApiResponse.ok(skillCenterService.update(id, request.version(),
                request.instructions(), request.rulesYaml(), request.description()));
    }

    @PostMapping("/{id}/submit-review")
    public ApiResponse<SkillView> submitReview(@PathVariable("id") String id) {
        return ApiResponse.ok(skillCenterService.submitReview(id));
    }

    @PostMapping("/{id}/review")
    public ApiResponse<SkillView> review(@PathVariable("id") String id,
                                         @Valid @RequestBody SkillReviewRequest request) {
        return ApiResponse.ok(skillCenterService.review(id, request.decision(), request.comment()));
    }

    @PostMapping("/{id}/enable")
    public ApiResponse<SkillView> enable(@PathVariable("id") String id) {
        return ApiResponse.ok(skillCenterService.enable(id));
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<SkillView> disable(@PathVariable("id") String id) {
        return ApiResponse.ok(skillCenterService.disable(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<SkillView> delete(@PathVariable("id") String id) {
        return ApiResponse.ok(skillCenterService.delete(id));
    }
}
