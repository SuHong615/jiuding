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
import com.suhong.jiuding.gateway.auth.TenantAuthUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Gateway-only exception mapping that must win over the shared {@code GlobalExceptionHandler}.
 *
 * <p>docs/13 §2.2: when tenant-service cannot be reached (or answers 5xx) during
 * tenant-account authentication the gateway must answer {@code 503} with the shared
 * {@code UPSTREAM_ERROR} code — not the generic 502 mapping and not a fake 401.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GatewayExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    @ExceptionHandler(TenantAuthUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleTenantAuthUnavailable(TenantAuthUnavailableException ex) {
        log.warn("tenant account authentication unavailable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error(ErrorCode.UPSTREAM_ERROR.code(),
                        "tenant authentication service unavailable", MDC.get("requestId")));
    }
}
