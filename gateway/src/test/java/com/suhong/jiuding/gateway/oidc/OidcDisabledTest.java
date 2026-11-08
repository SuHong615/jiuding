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
package com.suhong.jiuding.gateway.oidc;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** OIDC disabled behaviour (docs/16 §2/§5): status=false, authorize 404, callback 400. */
@SpringBootTest(properties = {
        "tenancy.auth.oidc.enabled=false",
        "tenancy.auth.oidc.issuer=",
        "tenancy.auth.oidc.client-id=",
        "tenancy.auth.oidc.client-secret="
})
@AutoConfigureMockMvc
class OidcDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void statusReportsDisabled() throws Exception {
        mockMvc.perform(get("/api/auth/oidc/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.loginUrl").doesNotExist());
    }

    @Test
    void authorizeReturns404WhenDisabled() throws Exception {
        mockMvc.perform(get("/api/auth/oidc/authorize"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void callbackReturns400WhenDisabled() throws Exception {
        mockMvc.perform(get("/api/auth/oidc/callback").param("code", "x").param("state", "y"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }
}
