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
package com.suhong.jiuding.tenant.support;

import com.suhong.jiuding.common.error.BizException;

/** Shared pagination parameter validation. */
public final class Paging {

    private Paging() {
    }

    public static void validate(int page, int size, int maxSize) {
        if (page < 0) {
            throw BizException.invalid("page must be >= 0");
        }
        if (size < 1 || size > maxSize) {
            throw BizException.invalid("size must be between 1 and " + maxSize);
        }
    }
}
