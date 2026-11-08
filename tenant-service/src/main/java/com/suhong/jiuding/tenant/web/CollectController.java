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
package com.suhong.jiuding.tenant.web;

import com.suhong.jiuding.common.api.ApiResponse;
import com.suhong.jiuding.common.api.PageResult;
import com.suhong.jiuding.tenant.api.CollectJobView;
import com.suhong.jiuding.tenant.api.CollectRequest;
import com.suhong.jiuding.tenant.service.CollectionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Tenant bulk collection API (inline JSON batch and CSV upload). */
@RestController
@RequestMapping("/api/tenants/collect")
public class CollectController {

    private final CollectionService collectionService;

    public CollectController(CollectionService collectionService) {
        this.collectionService = collectionService;
    }

    @PostMapping
    public ApiResponse<CollectJobView> collect(@RequestBody CollectRequest request) {
        return ApiResponses.ok(collectionService.startInline(request));
    }

    @PostMapping("/csv")
    public ApiResponse<CollectJobView> collectCsv(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "sourceName", required = false) String sourceName,
            @RequestParam(value = "autoSubmit", required = false) Boolean autoSubmit) {
        return ApiResponses.ok(collectionService.startCsv(file, sourceName, autoSubmit));
    }

    @GetMapping("/jobs")
    public ApiResponse<PageResult<CollectJobView>> jobs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponses.ok(collectionService.listJobs(page, size));
    }

    @GetMapping("/jobs/{id}")
    public ApiResponse<CollectJobView> job(@PathVariable String id) {
        return ApiResponses.ok(collectionService.getJob(id));
    }
}
