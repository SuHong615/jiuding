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
package com.suhong.jiuding.tenant.bootstrap;

import com.suhong.jiuding.tenant.platformuser.PlatformUserRepository;
import com.suhong.jiuding.tenant.platformuser.PlatformUserService;
import com.suhong.jiuding.tenant.support.Texts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Startup bootstrap of the first platform administrator (docs/23 section 5).
 *
 * <p>Reads {@code TENANCY_PLATFORM_BOOTSTRAP_USERNAME}, {@code ..._PASSWORD} and
 * {@code ..._ROLES}. It only creates a user when the {@code platform_users} table is empty and
 * a non blank password is supplied; there is no hard-coded account or password. The created
 * user is audited as {@code PLATFORM_USER_BOOTSTRAPPED}.
 */
@Component
public class PlatformUserBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlatformUserBootstrap.class);

    private final PlatformUserService userService;
    private final PlatformUserRepository repository;
    private final String username;
    private final String password;
    private final String roles;

    public PlatformUserBootstrap(PlatformUserService userService,
                                 PlatformUserRepository repository,
                                 @Value("${TENANCY_PLATFORM_BOOTSTRAP_USERNAME:}") String username,
                                 @Value("${TENANCY_PLATFORM_BOOTSTRAP_PASSWORD:}") String password,
                                 @Value("${TENANCY_PLATFORM_BOOTSTRAP_ROLES:}") String roles) {
        this.userService = userService;
        this.repository = repository;
        this.username = username;
        this.password = password;
        this.roles = roles;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrap();
    }

    /** @return true when a bootstrap user was created by this call. */
    public boolean bootstrap() {
        String name = Texts.blankToNull(username);
        String secret = Texts.blankToNull(password);
        if (name == null || secret == null) {
            log.debug("platform user bootstrap skipped: username or password environment variable is empty");
            return false;
        }
        if (repository.count() > 0) {
            log.info("platform user bootstrap skipped: platform_users is not empty");
            return false;
        }
        userService.bootstrap(name, secret, parseRoles(roles));
        log.info("platform user bootstrap created user '{}'", Texts.truncate(name, 64));
        return true;
    }

    static List<String> parseRoles(String raw) {
        String value = Texts.blankToNull(raw);
        if (value == null) {
            return List.of("PLATFORM_ADMIN");
        }
        List<String> parsed = new ArrayList<>();
        for (String part : value.split(",")) {
            String role = part.trim();
            if (!role.isEmpty()) {
                parsed.add(role.toUpperCase(Locale.ROOT));
            }
        }
        return parsed.isEmpty() ? List.of("PLATFORM_ADMIN") : parsed;
    }
}
