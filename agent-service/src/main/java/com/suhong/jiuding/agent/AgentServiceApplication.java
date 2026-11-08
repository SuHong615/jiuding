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
package com.suhong.jiuding.agent;

import com.suhong.jiuding.agent.config.TenancyAgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Agent service (port 8083): hosts the AgentScope-powered agent runtime, the
 * Skill registry / hot loader and the deterministic rule engine.
 *
 * <p>The service is intentionally servlet-based (spring-boot-starter-web) and
 * uses plain JDBC through {@code JdbcTemplate}; no JPA, no WebFlux, no Lombok.
 */
@SpringBootApplication
@ConfigurationPropertiesScan(basePackages = "com.suhong.jiuding.agent.config")
public class AgentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentServiceApplication.class, args);
    }
}
