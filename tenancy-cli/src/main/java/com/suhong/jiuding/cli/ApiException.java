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
package com.suhong.jiuding.cli;

/** Thrown when the gateway returned an error or could not be reached. */
public class ApiException extends RuntimeException {

    private final int exitCode;
    private final int apiCode;

    public ApiException(int exitCode, int apiCode, String message) {
        super(message);
        this.exitCode = exitCode;
        this.apiCode = apiCode;
    }

    public ApiException(int exitCode, int apiCode, String message, Throwable cause) {
        super(message, cause);
        this.exitCode = exitCode;
        this.apiCode = apiCode;
    }

    /** CLI exit code to use for this failure (see {@link ExitCodes}). */
    public int exitCode() {
        return exitCode;
    }

    /** Server-side business code, or the HTTP status when no envelope was available. */
    public int apiCode() {
        return apiCode;
    }
}
