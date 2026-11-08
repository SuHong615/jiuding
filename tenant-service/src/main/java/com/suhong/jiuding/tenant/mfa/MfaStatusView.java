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
package com.suhong.jiuding.tenant.mfa;

import com.suhong.jiuding.tenant.support.TimeUtil;

/** {@code GET /api/me/mfa} response (docs/16 section 3). */
public record MfaStatusView(boolean enabled, String confirmedAt) {

    static MfaStatusView of(MfaRow row) {
        if (row == null) {
            return new MfaStatusView(false, null);
        }
        return new MfaStatusView(row.enabled(), TimeUtil.iso(row.confirmedAt()));
    }
}
