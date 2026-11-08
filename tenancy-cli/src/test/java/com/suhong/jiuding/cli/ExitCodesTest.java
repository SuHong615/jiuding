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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Exit code contract: 0 success / 2 usage / 3 auth / 4 business / 5 network. */
class ExitCodesTest {

    @Test
    void httpStatusesMapToExitCodes() {
        assertEquals(ExitCodes.AUTH, ExitCodes.forHttpStatus(401));
        assertEquals(ExitCodes.AUTH, ExitCodes.forHttpStatus(403));
        assertEquals(ExitCodes.BUSINESS, ExitCodes.forHttpStatus(400));
        assertEquals(ExitCodes.BUSINESS, ExitCodes.forHttpStatus(404));
        assertEquals(ExitCodes.BUSINESS, ExitCodes.forHttpStatus(500));
        assertEquals(ExitCodes.NETWORK, ExitCodes.forHttpStatus(503));
        assertEquals(ExitCodes.OK, ExitCodes.forHttpStatus(200));
    }

    @Test
    void envelopeCodesMapToExitCodes() {
        assertEquals(ExitCodes.OK, ExitCodes.forEnvelopeCode(0));
        assertEquals(ExitCodes.AUTH, ExitCodes.forEnvelopeCode(40100));
        assertEquals(ExitCodes.AUTH, ExitCodes.forEnvelopeCode(40300));
        assertEquals(ExitCodes.BUSINESS, ExitCodes.forEnvelopeCode(40900));
        assertEquals(ExitCodes.BUSINESS, ExitCodes.forEnvelopeCode(54000));
    }

    @Test
    void throwablesMapToExitCodes() {
        assertEquals(ExitCodes.USAGE, ExitCodes.forThrowable(new CliUsageException("bad args")));
        assertEquals(ExitCodes.AUTH, ExitCodes.forThrowable(new ApiException(ExitCodes.AUTH, 40100, "nope")));
        assertEquals(ExitCodes.BUSINESS, ExitCodes.forThrowable(new ApiException(ExitCodes.BUSINESS, 40900, "conflict")));
        assertEquals(ExitCodes.NETWORK, ExitCodes.forThrowable(new IOException("connection refused")));
        assertEquals(ExitCodes.NETWORK, ExitCodes.forThrowable(new HttpTimeoutException("timeout")));
        assertEquals(ExitCodes.BUSINESS, ExitCodes.forThrowable(new IllegalStateException("boom")));
    }
}
