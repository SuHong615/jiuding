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

/** SCIM protocol error carrying the RFC7644 HTTP status (returned with the SCIM Error schema). */
public class ScimException extends RuntimeException {

    private final int status;

    public ScimException(int status, String detail) {
        super(detail);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public static ScimException badRequest(String detail) {
        return new ScimException(400, detail);
    }

    public static ScimException unauthorized(String detail) {
        return new ScimException(401, detail);
    }

    public static ScimException notFound(String detail) {
        return new ScimException(404, detail);
    }

    public static ScimException conflict(String detail) {
        return new ScimException(409, detail);
    }
}
