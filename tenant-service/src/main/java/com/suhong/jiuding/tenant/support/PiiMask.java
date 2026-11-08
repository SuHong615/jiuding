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

/**
 * PII masking for account responses (docs/14 section 3.2).
 *
 * <ul>
 *   <li>email: {@code alice@acme.io} -&gt; {@code a***@acme.io}</li>
 *   <li>phone: {@code 13812341234} -&gt; {@code 138****1234}</li>
 * </ul>
 */
public final class PiiMask {

    private PiiMask() {
    }

    public static String email(String email) {
        if (email == null || email.isBlank()) {
            return email;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return email.charAt(0) + "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    public static String phone(String phone) {
        if (phone == null || phone.isBlank()) {
            return phone;
        }
        if (phone.length() >= 7) {
            return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
        }
        return "***";
    }
}
