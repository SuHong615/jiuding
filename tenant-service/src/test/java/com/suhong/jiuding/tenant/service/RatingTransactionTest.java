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
package com.suhong.jiuding.tenant.service;

import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.error.ErrorCode;
import com.suhong.jiuding.tenant.api.RatingAssessRequest;
import com.suhong.jiuding.tenant.api.RatingView;
import com.suhong.jiuding.tenant.repository.RatingRepository;
import com.suhong.jiuding.tenant.repository.RatingRow;
import com.suhong.jiuding.tenant.repository.TenantRepository;
import com.suhong.jiuding.tenant.repository.TenantRow;
import com.suhong.jiuding.tenant.support.TenantFixtures;
import com.suhong.jiuding.tenant.support.TestSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;

/**
 * L-1 regression: the rating history insert and the denormalised current rating update run
 * in one transaction, so a failed current-rating CAS must not leave an orphan history row.
 */
@SpringBootTest
@ActiveProfiles("test")
class RatingTransactionTest {

    @Autowired
    private RatingService ratingService;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private RatingRepository ratingRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @SpyBean
    private TenantRepository tenantRepository;

    private String tenantId;

    @BeforeEach
    void setUp() {
        TestSecurity.platformAdmin();
        TenantFixtures.clean(jdbc);
        tenantId = TenantFixtures.createTenant(tenantService, "t-tx", "Transaction Tenant").id();
    }

    @AfterEach
    void tearDown() {
        reset(tenantRepository);
        TestSecurity.clear();
    }

    @Test
    void conflictedCurrentRatingUpdateRollsTheHistoryInsertBack() {
        doReturn(0).when(tenantRepository).updateFields(anyString(), anyLong(), anyMap());

        BizException conflict = catchThrowableOfType(
                () -> ratingService.assess(tenantId, new RatingAssessRequest("rollback")),
                BizException.class);
        assertThat(conflict).isNotNull();
        assertThat(conflict.errorCode()).isEqualTo(ErrorCode.CONFLICT);

        assertThat(ratingRepository.count(tenantId)).isZero();
        assertThat(ratingRepository.history(tenantId, 0, 20)).isEmpty();
        reset(tenantRepository);
        TenantRow row = tenantRepository.findById(tenantId).orElseThrow();
        assertThat(row.ratingGrade()).isNull();
        assertThat(row.ratingScore()).isNull();
    }

    @Test
    void concurrentAssessmentsKeepCurrentRatingConsistentWithHistory() throws Exception {
        int writers = 2;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int index = 0; index < writers; index++) {
            futures.add(pool.submit(() -> {
                TestSecurity.platformAdmin();
                try {
                    start.await();
                    ratingService.assess(tenantId, new RatingAssessRequest("concurrent"));
                } catch (BizException expectedConflict) {
                    // Optimistic lock conflict: the transaction rolled the history insert back.
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    TestSecurity.clear();
                }
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(20, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        TestSecurity.platformAdmin();
        RatingView current = ratingService.current(tenantId);
        RatingRow latest = ratingRepository.latest(tenantId).orElseThrow();
        assertThat(current.grade()).isEqualTo(latest.grade());
        assertThat(current.score()).isEqualTo(latest.score());
        assertThat(ratingRepository.count(tenantId)).isGreaterThanOrEqualTo(1);

        TenantRow row = tenantRepository.findById(tenantId).orElseThrow();
        assertThat(row.ratingGrade()).isEqualTo(latest.grade());
        assertThat(row.ratingScore()).isEqualTo(latest.score());
    }
}
