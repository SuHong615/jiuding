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
package com.suhong.jiuding.tenant.quota;

import com.suhong.jiuding.common.api.ApiResponse;
import com.suhong.jiuding.common.error.ErrorCode;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps quota violations to HTTP 409 with the shared {@code QUOTA_EXCEEDED} error code
 * (docs/17 section 4). The common handler maps {@link ErrorCode#QUOTA_EXCEEDED} to 429 for
 * gateway rate limiting, so create endpoints need this explicit 409 conflict mapping.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class QuotaExceptionHandler {

    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleQuota(QuotaExceededException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ErrorCode.QUOTA_EXCEEDED.code(), ex.getMessage(),
                        MDC.get("requestId")));
    }
}
