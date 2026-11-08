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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/**
 * Maps SCIM endpoint failures onto the RFC7644 Error schema
 * {@code {schemas:[...Error], status, detail}} (docs/16 section 7.1). Restricted to the SCIM
 * controllers so the rest of tenant-service keeps the unified ApiResponse envelope.
 */
@RestControllerAdvice(assignableTypes = {
        ScimUserController.class,
        ScimGroupController.class,
        ScimServiceProviderConfigController.class
})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ScimExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ScimExceptionHandler.class);

    @ExceptionHandler(ScimException.class)
    public ResponseEntity<Map<String, Object>> handleScim(ScimException ex) {
        return response(ex.status(), ex.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception ex) {
        return response(400, "malformed SCIM request: " + ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        log.error("unhandled SCIM error", ex);
        return response(500, "internal SCIM error");
    }

    private ResponseEntity<Map<String, Object>> response(int status, String detail) {
        return ResponseEntity.status(status)
                .contentType(MediaType.parseMediaType(ScimSupport.SCIM_JSON))
                .body(ScimSupport.error(status, detail));
    }
}
