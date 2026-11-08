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
package com.suhong.jiuding.gateway.web;

import com.suhong.jiuding.common.api.ApiResponse;
import com.suhong.jiuding.common.error.ErrorCode;
import com.suhong.jiuding.common.security.Headers;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Blocks the service-internal API surface at the edge.
 *
 * <p>{@code /internal/**} is for service-to-service calls only (authenticated with
 * {@code X-Internal-Token} inside each service). It must never be reachable through the
 * gateway: there is no proxy route for it, and answering with a Spring 500 would both
 * leak that the path exists and mask the misconfiguration. This handler therefore returns
 * a plain {@code 404} for every method under both {@code /internal} and {@code /internal/**},
 * with no downstream call, regardless of credentials.
 *
 * <p>{@code /internal/**} is also whitelisted in {@code tenancy.security.whitelist} so the
 * shared {@code AuthFilter} does not turn the request into a 401/403 before it reaches
 * this handler; the visible behaviour stays a uniform 404 (existence is not disclosed).
 */
@RestController
public class InternalPathController {

    @RequestMapping({"/internal", "/internal/**"})
    public ResponseEntity<ApiResponse<Void>> internal(HttpServletRequest request) {
        String requestId = request.getHeader(Headers.REQUEST_ID);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header(Headers.REQUEST_ID, requestId)
                .body(ApiResponse.error(ErrorCode.NOT_FOUND.code(), "not found", requestId));
    }
}
