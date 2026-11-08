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
package com.suhong.jiuding.tenant.permission;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the permission required to invoke an API method (docs/11 section 5, v9 phase 1).
 *
 * <p>Values are semantic aliases from the v5 permission catalogue such as
 * {@code org:read} or {@code account:security}; literal codes ({@code ORG_READ}) are also
 * accepted. Several values mean "any of" and are resolved by {@link PermissionCatalog}.
 * A platform administrator (or a trusted machine caller) always passes.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    /** One or more permission aliases/codes; any of them grants access. */
    String[] value();
}
