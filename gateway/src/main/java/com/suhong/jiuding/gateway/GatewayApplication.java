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
package com.suhong.jiuding.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Edge gateway: the only public entry point of the platform.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>issue / verify JWTs ({@code /api/auth/login}, {@code /api/auth/me});</li>
 *   <li>reverse-proxy REST and MCP traffic to downstream services, streaming SSE
 *       chunks without buffering;</li>
 *   <li>aggregate {@code /api/platform/**} snapshots with per-service degradation.</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.suhong.jiuding.gateway")
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
