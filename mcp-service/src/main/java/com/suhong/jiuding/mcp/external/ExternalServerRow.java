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

import java.time.LocalDateTime;

/** Row of {@code mcp_external_servers} (docs/15 §4.1). */
public record ExternalServerRow(String id,
                                String name,
                                String transport,
                                String url,
                                String headersJson,
                                String status,
                                String createdBy,
                                LocalDateTime createdAt,
                                LocalDateTime lastCheckAt,
                                Boolean lastCheckOk) {
}
