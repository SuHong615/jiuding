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
package com.suhong.jiuding.tenant.domain;

import com.suhong.jiuding.tenant.repository.TenantRow;

import java.util.Locale;

/**
 * Deterministic five-dimension weighted rating model (docs/11 section 4).
 *
 * <table>
 *   <tr><th>dimension</th><th>weight</th></tr>
 *   <tr><td>business</td><td>30%</td></tr>
 *   <tr><td>compliance</td><td>25%</td></tr>
 *   <tr><td>financial</td><td>20%</td></tr>
 *   <tr><td>security</td><td>15%</td></tr>
 *   <tr><td>service</td><td>10%</td></tr>
 * </table>
 *
 * <p>Every input is a persisted tenant attribute (plan, quota, risk, contact data,
 * attributes map) so the same tenant always produces the same base score. The optional
 * agent-service {@code tenant-rating} skill may only add a bounded adjustment on top.
 */
public final class RatingCalculator {

    private RatingCalculator() {
    }

    public static Result calculate(TenantRow tenant, int orgUnitCount, int accountCount) {
        Plan plan = Plan.parseOrDefault(tenant.plan());
        boolean enterprise = plan == Plan.ENTERPRISE;

        int business = 40 + planBonus(plan);
        int seats = valueOrZero(tenant.quotaSeats());
        if (seats >= 500) {
            business += 8;
        } else if (seats >= 100) {
            business += 5;
        } else if (seats >= 20) {
            business += 2;
        } else if (seats >= 1) {
            business += 1;
        }
        int storage = valueOrZero(tenant.quotaStorageGb());
        if (storage >= 1000) {
            business += 4;
        } else if (storage >= 100) {
            business += 2;
        } else if (storage >= 10) {
            business += 1;
        }
        if (orgUnitCount >= 20) {
            business += 3;
        } else if (orgUnitCount >= 5) {
            business += 2;
        } else if (orgUnitCount >= 1) {
            business += 1;
        }
        if (accountCount >= 100) {
            business += 3;
        } else if (accountCount >= 20) {
            business += 2;
        } else if (accountCount >= 1) {
            business += 1;
        }

        int compliance = 90;
        if (tenant.riskScore() != null) {
            compliance = 100 - tenant.riskScore();
        }
        RiskLevel riskLevel = tenant.riskLevel() == null ? null : RiskLevel.parse(tenant.riskLevel());
        if (riskLevel == RiskLevel.HIGH) {
            compliance -= 20;
        } else if (riskLevel == RiskLevel.MEDIUM) {
            compliance -= 10;
        }
        if (isBlacklisted(tenant.attributesJson())) {
            compliance = Math.min(compliance, 20);
        }

        int financial = switch (plan) {
            case FREE -> 50;
            case PRO -> 70;
            case ENTERPRISE -> 90;
        };

        int security = 60;
        if (tenant.contactName() != null && !tenant.contactName().isBlank()) {
            security += 10;
        }
        if (looksLikeEmail(tenant.contactEmail())) {
            security += 15;
        }
        if (tenant.contactPhone() != null && !tenant.contactPhone().isBlank()) {
            security += 10;
        }
        if (hasAttribute(tenant.attributesJson(), "mfaEnabled", "true")) {
            security += 5;
        }

        int service = 75;
        if (enterprise) {
            service += 10;
        }
        if (hasAttribute(tenant.attributesJson(), "slaTier", "GOLD")
                || hasAttribute(tenant.attributesJson(), "slaTier", "PLATINUM")) {
            service += 10;
        }

        Dims dims = new Dims(clamp(business), clamp(compliance), clamp(financial),
                clamp(security), clamp(service));
        return withScore(dims);
    }

    /** Applies an optional skill adjustment (bounded) and recomputes the grade. */
    public static Result adjust(Dims dims, int adjustment) {
        int bounded = Math.max(-15, Math.min(15, adjustment));
        int score = clamp(weighted(dims) + bounded);
        return new Result(dims, score, RatingGrade.fromScore(score));
    }

    public static Result withScore(Dims dims) {
        int score = clamp(weighted(dims));
        return new Result(dims, score, RatingGrade.fromScore(score));
    }

    private static int weighted(Dims dims) {
        return (int) Math.round((dims.business() * 30.0
                + dims.compliance() * 25.0
                + dims.financial() * 20.0
                + dims.security() * 15.0
                + dims.service() * 10.0) / 100.0);
    }

    private static int planBonus(Plan plan) {
        return switch (plan) {
            case FREE -> 20;
            case PRO -> 35;
            case ENTERPRISE -> 50;
        };
    }

    private static boolean looksLikeEmail(String value) {
        if (value == null) {
            return false;
        }
        String email = value.trim();
        int at = email.indexOf('@');
        return at > 0 && email.indexOf('.', at) > at + 1;
    }

    private static boolean isBlacklisted(String attributesJson) {
        return hasAttribute(attributesJson, "blacklisted", "true")
                || hasAttribute(attributesJson, "blacklist", "true");
    }

    private static boolean hasAttribute(String attributesJson, String key, String expected) {
        if (attributesJson == null || attributesJson.isBlank()) {
            return false;
        }
        String needle = "\"" + key + "\"";
        int index = attributesJson.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
        if (index < 0) {
            return false;
        }
        String tail = attributesJson.substring(index + needle.length());
        return tail.toLowerCase(Locale.ROOT)
                .matches("(?s)\\s*:\\s*\"?" + java.util.regex.Pattern.quote(expected.toLowerCase(Locale.ROOT))
                        + "\"?\\s*[,}].*");
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    /** Five raw dimension scores (0-100). */
    public record Dims(int business, int compliance, int financial, int security, int service) {
    }

    /** Weighted result: dimensions, final score and mapped grade. */
    public record Result(Dims dims, int score, RatingGrade grade) {
    }
}
