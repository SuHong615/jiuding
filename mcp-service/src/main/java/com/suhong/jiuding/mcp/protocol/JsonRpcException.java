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
package com.suhong.jiuding.mcp.protocol;

/** A JSON-RPC protocol-level error (HTTP stays 200, the error travels in the JSON-RPC envelope). */
public class JsonRpcException extends RuntimeException {

    private final int code;
    private final transient Object data;

    public JsonRpcException(int code, String message) {
        this(code, message, null);
    }

    public JsonRpcException(int code, String message, Object data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    public int code() {
        return code;
    }

    public Object data() {
        return data;
    }
}
