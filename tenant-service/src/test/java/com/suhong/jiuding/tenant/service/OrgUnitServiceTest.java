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
import com.suhong.jiuding.tenant.api.AccountCreateRequest;
import com.suhong.jiuding.tenant.api.OrgUnitCreateRequest;
import com.suhong.jiuding.tenant.api.OrgUnitMoveRequest;
import com.suhong.jiuding.tenant.api.OrgUnitUpdateRequest;
import com.suhong.jiuding.tenant.api.OrgUnitView;
import com.suhong.jiuding.tenant.api.TenantView;
import com.suhong.jiuding.tenant.support.TenantFixtures;
import com.suhong.jiuding.tenant.support.TestSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@SpringBootTest
@ActiveProfiles("test")
class OrgUnitServiceTest {

    @Autowired
    private OrgUnitService orgUnitService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private JdbcTemplate jdbc;

    private String tenantId;

    @BeforeEach
    void setUp() {
        TestSecurity.platformAdmin();
        TenantFixtures.clean(jdbc);
        tenantId = TenantFixtures.createTenant(tenantService, "t-acme", "Acme").id();
    }

    @AfterEach
    void tearDown() {
        TestSecurity.clear();
    }

    @Test
    void materialisedPathTreeAndSubtreeAccountCounts() {
        OrgUnitView root = create(null, "HQ", "Headquarters", "COMPANY");
        OrgUnitView branch = create(root.id(), "SH", "Shanghai Branch", "BRANCH");
        OrgUnitView dept = create(branch.id(), "ENG", "Engineering", "DEPARTMENT");

        assertThat(root.depth()).isZero();
        assertThat(root.path()).isEqualTo("/" + root.id());
        assertThat(branch.depth()).isEqualTo(1);
        assertThat(branch.path()).isEqualTo(root.path() + "/" + branch.id());
        assertThat(dept.depth()).isEqualTo(2);
        assertThat(dept.path()).isEqualTo(branch.path() + "/" + dept.id());

        accountService.create(tenantId, account("alice", dept.id()));
        accountService.create(tenantId, account("bob", root.id()));

        List<OrgUnitView> flat = orgUnitService.list(tenantId, "flat", null, null);
        assertThat(flat).hasSize(3);
        assertThat(flat).extracting(OrgUnitView::type)
                .containsExactlyInAnyOrder("COMPANY", "BRANCH", "DEPARTMENT");

        List<OrgUnitView> tree = orgUnitService.list(tenantId, "tree", null, null);
        assertThat(tree).hasSize(1);
        OrgUnitView treeRoot = tree.get(0);
        assertThat(treeRoot.id()).isEqualTo(root.id());
        assertThat(treeRoot.childCount()).isEqualTo(1);
        assertThat(treeRoot.accountCount()).isEqualTo(2);
        assertThat(treeRoot.children()).hasSize(1);
        OrgUnitView treeBranch = treeRoot.children().get(0);
        assertThat(treeBranch.id()).isEqualTo(branch.id());
        assertThat(treeBranch.accountCount()).isEqualTo(1);
        assertThat(treeBranch.children()).hasSize(1);
        assertThat(treeBranch.children().get(0).id()).isEqualTo(dept.id());
    }

    @Test
    void codeIsUniquePerTenantAndDepthIsLimitedToSix() {
        create(null, "HQ", "Headquarters", "COMPANY");
        BizException duplicate = catchThrowableOfType(
                () -> create(null, "HQ", "Another HQ", "COMPANY"), BizException.class);
        assertThat(duplicate).isNotNull();
        assertThat(duplicate.errorCode()).isEqualTo(ErrorCode.CONFLICT);

        String parentId = null;
        for (int depth = 0; depth <= 6; depth++) {
            parentId = create(parentId, "L" + depth, "Level " + depth, "DEPARTMENT").id();
        }
        String deepest = parentId;
        BizException tooDeep = catchThrowableOfType(
                () -> create(deepest, "L7", "Level 7", "TEAM"), BizException.class);
        assertThat(tooDeep).isNotNull();
        assertThat(tooDeep.errorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
    }

    @Test
    void moveRewritesTheWholeSubtreePathAndDepth() {
        OrgUnitView rootA = create(null, "A", "Root A", "COMPANY");
        OrgUnitView branch = create(rootA.id(), "B", "Branch B", "BRANCH");
        OrgUnitView team = create(branch.id(), "C", "Team C", "TEAM");
        OrgUnitView rootD = create(null, "D", "Root D", "COMPANY");

        OrgUnitView moved = orgUnitService.move(tenantId, branch.id(),
                new OrgUnitMoveRequest(rootD.id()));

        assertThat(moved.depth()).isEqualTo(1);
        assertThat(moved.path()).isEqualTo(rootD.path() + "/" + branch.id());
        OrgUnitView movedTeam = orgUnitService.get(tenantId, team.id());
        assertThat(movedTeam.depth()).isEqualTo(2);
        assertThat(movedTeam.path()).isEqualTo(moved.path() + "/" + team.id());
        assertThat(movedTeam.parentId()).isEqualTo(branch.id());
    }

    @Test
    void moveIntoOwnSubtreeIsRejected() {
        OrgUnitView root = create(null, "A", "Root", "COMPANY");
        OrgUnitView child = create(root.id(), "B", "Child", "BRANCH");
        OrgUnitView grandChild = create(child.id(), "C", "Grandchild", "TEAM");

        BizException intoSelf = catchThrowableOfType(
                () -> orgUnitService.move(tenantId, root.id(), new OrgUnitMoveRequest(grandChild.id())),
                BizException.class);
        assertThat(intoSelf).isNotNull();
        assertThat(intoSelf.errorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT);

        BizException intoOwnChild = catchThrowableOfType(
                () -> orgUnitService.move(tenantId, root.id(), new OrgUnitMoveRequest(child.id())),
                BizException.class);
        assertThat(intoOwnChild).isNotNull();
        assertThat(intoOwnChild.errorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
    }

    @Test
    void deleteRequiresNoChildrenAndNoAccounts() {
        OrgUnitView root = create(null, "A", "Root", "COMPANY");
        create(root.id(), "B", "Child", "BRANCH");
        BizException withChild = catchThrowableOfType(
                () -> orgUnitService.delete(tenantId, root.id()), BizException.class);
        assertThat(withChild).isNotNull();
        assertThat(withChild.errorCode()).isEqualTo(ErrorCode.CONFLICT);

        OrgUnitView leaf = create(null, "L", "Leaf", "TEAM");
        accountService.create(tenantId, account("carol", leaf.id()));
        BizException withAccount = catchThrowableOfType(
                () -> orgUnitService.delete(tenantId, leaf.id()), BizException.class);
        assertThat(withAccount).isNotNull();
        assertThat(withAccount.errorCode()).isEqualTo(ErrorCode.CONFLICT);

        OrgUnitView empty = create(null, "E", "Empty", "TEAM");
        orgUnitService.delete(tenantId, empty.id());
        BizException gone = catchThrowableOfType(
                () -> orgUnitService.get(tenantId, empty.id()), BizException.class);
        assertThat(gone).isNotNull();
        assertThat(gone.errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void updateAndCrossTenantAccessAreGuarded() {
        OrgUnitView root = create(null, "A", "Root", "COMPANY");
        OrgUnitView updated = orgUnitService.update(tenantId, root.id(),
                new OrgUnitUpdateRequest("Renamed Root", "COMPANY", 5, null));
        assertThat(updated.name()).isEqualTo("Renamed Root");
        assertThat(updated.sortOrder()).isEqualTo(5);

        TenantView other = TenantFixtures.createTenant(tenantService, "t-beta", "Beta");
        TestSecurity.tenantAdmin("t-acme");
        BizException forbidden = catchThrowableOfType(
                () -> orgUnitService.list(other.id(), "flat", null, null), BizException.class);
        assertThat(forbidden).isNotNull();
        assertThat(forbidden.errorCode()).isEqualTo(ErrorCode.FORBIDDEN);

        // The same tenant administrator can address its own tenant by code or id.
        assertThat(orgUnitService.list("t-acme", "flat", null, null)).hasSize(1);
    }

    private OrgUnitView create(String parentId, String code, String name, String type) {
        return orgUnitService.create(tenantId,
                new OrgUnitCreateRequest(parentId, code, name, type, 0, null));
    }

    private AccountCreateRequest account(String username, String orgUnitId) {
        return new AccountCreateRequest(username, username, username + "@acme.io", null,
                orgUnitId, "MEMBER", "Passw0rd123");
    }
}
