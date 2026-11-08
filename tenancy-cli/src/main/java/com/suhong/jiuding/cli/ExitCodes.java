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

import java.io.IOException;

/**
 * Process exit codes shared by every command.
 *
 * <pre>
 *   0 success
 *   2 invalid arguments / usage
 *   3 authentication or authorization failure
 *   4 business error (server returned a non-zero business code)
 *   5 network error (connection refused, timeout, DNS, ...)
 * </pre>
 */
public final class ExitCodes {

    public static final int OK = 0;
    public static final int USAGE = 2;
    public static final int AUTH = 3;
    public static final int BUSINESS = 4;
    public static final int NETWORK = 5;

    /** ErrorCode.UNAUTHORIZED */
    public static final int CODE_UNAUTHORIZED = 40100;
    /** ErrorCode.FORBIDDEN */
    public static final int CODE_FORBIDDEN = 40300;

    private ExitCodes() {
    }

    /** Maps an HTTP status to a CLI exit code. */
    public static int forHttpStatus(int httpStatus) {
        if (httpStatus == 401 || httpStatus == 403) {
            return AUTH;
        }
        // 503 Service Unavailable is a downstream/reachability problem, not a business error.
        if (httpStatus == 503) {
            return NETWORK;
        }
        if (httpStatus >= 400) {
            return BUSINESS;
        }
        return OK;
    }

    /** Maps a tenancy {@code ApiResponse.code} to a CLI exit code. */
    public static int forEnvelopeCode(int envelopeCode) {
        if (envelopeCode == 0) {
            return OK;
        }
        if (envelopeCode == CODE_UNAUTHORIZED || envelopeCode == CODE_FORBIDDEN) {
            return AUTH;
        }
        return BUSINESS;
    }

    /** Maps a thrown exception to a CLI exit code. */
    public static int forThrowable(Throwable throwable) {
        Throwable t = throwable;
        while (t != null) {
            if (t instanceof CliUsageException) {
                return USAGE;
            }
            if (t instanceof ApiException api) {
                return api.exitCode();
            }
            if (t instanceof IOException) {
                return NETWORK;
            }
            t = t.getCause() == t ? null : t.getCause();
        }
        return BUSINESS;
    }
}
