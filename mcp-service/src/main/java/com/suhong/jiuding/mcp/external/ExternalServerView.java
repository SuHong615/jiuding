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
package com.suhong.jiuding.mcp.external;

import com.suhong.jiuding.mcp.support.Iso8601;

/** Public projection of a registered external MCP server. */
public record ExternalServerView(String id,
                                 String name,
                                 String transport,
                                 String url,
                                 String status,
                                 String createdBy,
                                 String createdAt,
                                 String lastCheckAt,
                                 Boolean lastCheckOk) {

    public static ExternalServerView of(ExternalServerRow row) {
        return new ExternalServerView(row.id(), row.name(), row.transport(), row.url(), row.status(),
                row.createdBy(), Iso8601.format(row.createdAt()),
                Iso8601.format(row.lastCheckAt()), row.lastCheckOk());
    }
}
