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
package com.suhong.jiuding.tenant.scim;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** SCIM 2.0 Groups endpoint (docs/16 section 7.1), authenticated by a tenant SCIM token. */
@RestController
@RequestMapping(value = "/scim/v2/Groups", produces = ScimSupport.SCIM_JSON)
public class ScimGroupController {

    private final ScimTokenService tokenService;
    private final ScimGroupService groupService;

    public ScimGroupController(ScimTokenService tokenService, ScimGroupService groupService) {
        this.tokenService = tokenService;
        this.groupService = groupService;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String filter,
                                    @RequestParam(required = false) Integer startIndex,
                                    @RequestParam(required = false) Integer count,
                                    HttpServletRequest request) {
        ScimTokenService.ScimPrincipal principal = authenticate(request);
        return groupService.list(principal.tenantId(), filter, startIndex, count);
    }

    @PostMapping(consumes = {MediaType.APPLICATION_JSON_VALUE, ScimSupport.SCIM_JSON})
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body,
                                                      HttpServletRequest request) {
        ScimTokenService.ScimPrincipal principal = authenticate(request);
        Map<String, Object> resource = groupService.create(principal.tenantId(), body, actor(principal));
        return ResponseEntity.status(201)
                .header(HttpHeaders.LOCATION, "/scim/v2/Groups/" + resource.get("id"))
                .contentType(MediaType.parseMediaType(ScimSupport.SCIM_JSON))
                .body(resource);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id, HttpServletRequest request) {
        ScimTokenService.ScimPrincipal principal = authenticate(request);
        return groupService.get(principal.tenantId(), id);
    }

    @PutMapping(value = "/{id}", consumes = {MediaType.APPLICATION_JSON_VALUE, ScimSupport.SCIM_JSON})
    public Map<String, Object> replace(@PathVariable String id,
                                       @RequestBody Map<String, Object> body,
                                       HttpServletRequest request) {
        ScimTokenService.ScimPrincipal principal = authenticate(request);
        return groupService.replace(principal.tenantId(), id, body, actor(principal));
    }

    @PatchMapping(value = "/{id}", consumes = {MediaType.APPLICATION_JSON_VALUE, ScimSupport.SCIM_JSON})
    public Map<String, Object> patch(@PathVariable String id,
                                     @RequestBody Map<String, Object> body,
                                     HttpServletRequest request) {
        ScimTokenService.ScimPrincipal principal = authenticate(request);
        return groupService.patch(principal.tenantId(), id, body, actor(principal));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, HttpServletRequest request) {
        ScimTokenService.ScimPrincipal principal = authenticate(request);
        groupService.delete(principal.tenantId(), id, actor(principal));
        return ResponseEntity.noContent().build();
    }

    private ScimTokenService.ScimPrincipal authenticate(HttpServletRequest request) {
        return tokenService.authenticateTenant(request.getHeader(HttpHeaders.AUTHORIZATION));
    }

    private String actor(ScimTokenService.ScimPrincipal principal) {
        return "scim:" + principal.prefix();
    }
}
