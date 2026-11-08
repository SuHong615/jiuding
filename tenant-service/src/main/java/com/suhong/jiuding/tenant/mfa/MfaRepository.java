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

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Pure JdbcTemplate persistence for the MFA tables (docs/16 section 3). */
@Repository
public class MfaRepository {

    private static final RowMapper<MfaRow> MFA_MAPPER = (rs, rowNum) -> new MfaRow(
            rs.getString("account_id"),
            rs.getString("tenant_id"),
            rs.getString("secret_base32"),
            rs.getBoolean("enabled"),
            rs.getString("recovery_codes_hash"),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("confirmed_at", LocalDateTime.class),
            rs.getObject("last_used_at", LocalDateTime.class));

    private static final RowMapper<MfaChallengeRow> CHALLENGE_MAPPER = (rs, rowNum) -> new MfaChallengeRow(
            rs.getString("id"),
            rs.getString("account_id"),
            rs.getString("tenant_id"),
            rs.getString("code_hash"),
            rs.getObject("expires_at", LocalDateTime.class),
            rs.getInt("attempts"),
            rs.getObject("consumed_at", LocalDateTime.class));

    private final JdbcTemplate jdbc;

    public MfaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ account_mfa

    public Optional<MfaRow> findMfa(String accountId) {
        List<MfaRow> rows = jdbc.query("SELECT account_id, tenant_id, secret_base32, enabled,"
                + " recovery_codes_hash, created_at, confirmed_at, last_used_at"
                + " FROM account_mfa WHERE account_id = ?", MFA_MAPPER, accountId);
        return rows.stream().findFirst();
    }

    /** Creates a pending (not enabled) MFA enrollment or replaces a previously disabled one. */
    public void upsertSetup(String accountId, String tenantId, String secretBase32,
                            String recoveryCodesHash, LocalDateTime now) {
        int updated = jdbc.update("UPDATE account_mfa SET tenant_id = ?, secret_base32 = ?,"
                        + " enabled = FALSE, recovery_codes_hash = ?, created_at = ?,"
                        + " confirmed_at = NULL, last_used_at = NULL WHERE account_id = ?",
                tenantId, secretBase32, recoveryCodesHash, now, accountId);
        if (updated == 0) {
            jdbc.update("INSERT INTO account_mfa (account_id, tenant_id, secret_base32, enabled,"
                            + " recovery_codes_hash, created_at, confirmed_at, last_used_at)"
                            + " VALUES (?,?,?,?,?,?,?,?)",
                    accountId, tenantId, secretBase32, false, recoveryCodesHash, now, null, null);
        }
    }

    public void enable(String accountId, LocalDateTime confirmedAt) {
        jdbc.update("UPDATE account_mfa SET enabled = TRUE, confirmed_at = ?, last_used_at = ?"
                + " WHERE account_id = ?", confirmedAt, confirmedAt, accountId);
    }

    public void disable(String accountId) {
        jdbc.update("UPDATE account_mfa SET enabled = FALSE, recovery_codes_hash = NULL,"
                + " confirmed_at = NULL WHERE account_id = ?", accountId);
    }

    public void updateRecoveryCodes(String accountId, String recoveryCodesHash) {
        jdbc.update("UPDATE account_mfa SET recovery_codes_hash = ? WHERE account_id = ?",
                recoveryCodesHash, accountId);
    }

    public void updateLastUsed(String accountId, LocalDateTime usedAt) {
        jdbc.update("UPDATE account_mfa SET last_used_at = ? WHERE account_id = ?", usedAt, accountId);
    }

    // ------------------------------------------------------------------ mfa_challenges

    public void insertChallenge(MfaChallengeRow row) {
        jdbc.update("INSERT INTO mfa_challenges (id, account_id, tenant_id, code_hash, expires_at,"
                        + " attempts, consumed_at) VALUES (?,?,?,?,?,?,?)",
                row.id(), row.accountId(), row.tenantId(), row.codeHash(), row.expiresAt(),
                row.attempts(), row.consumedAt());
    }

    public Optional<MfaChallengeRow> findChallenge(String id) {
        List<MfaChallengeRow> rows = jdbc.query("SELECT id, account_id, tenant_id, code_hash,"
                + " expires_at, attempts, consumed_at FROM mfa_challenges WHERE id = ?",
                CHALLENGE_MAPPER, id);
        return rows.stream().findFirst();
    }

    public void recordAttempt(String id, int attempts, LocalDateTime consumedAt) {
        jdbc.update("UPDATE mfa_challenges SET attempts = ?, consumed_at = ? WHERE id = ?",
                attempts, consumedAt, id);
    }
}
