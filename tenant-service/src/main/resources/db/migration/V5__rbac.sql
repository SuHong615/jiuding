-- Copyright 2026 suhong
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--
-- @author suhong

-- v5 RBAC (docs/15-v5-skill-mcp-onboarding-rbac.md section 7.1).
-- H2 / PostgreSQL compatible ANSI subset: VARCHAR primary keys, BOOLEAN, no sequences.

CREATE TABLE permissions (
  code        VARCHAR(64) PRIMARY KEY,
  name        VARCHAR(64),
  group_code  VARCHAR(32),
  description VARCHAR(256)
);

CREATE TABLE roles (
  code        VARCHAR(64) PRIMARY KEY,
  name        VARCHAR(64),
  scope       VARCHAR(16),
  builtin     BOOLEAN,
  status      VARCHAR(16),
  description VARCHAR(256)
);

CREATE TABLE role_permissions (
  role_code       VARCHAR(64) NOT NULL,
  permission_code VARCHAR(64) NOT NULL,
  PRIMARY KEY (role_code, permission_code)
);

CREATE INDEX idx_role_permissions_role ON role_permissions(role_code);

-- ---------------------------------------------------------------- permission catalogue
INSERT INTO permissions (code, name, group_code, description) VALUES
  ('TENANT_READ', '查看租户', 'TENANT', '查看租户列表与详情'),
  ('TENANT_UPDATE', '编辑租户', 'TENANT', '编辑租户资料'),
  ('TENANT_SUBMIT', '提交审批', 'TENANT', '提交租户审批'),
  ('TENANT_LIFECYCLE', '租户启停', 'TENANT', '停用/启用/终止租户'),
  ('TENANT_DELETE', '删除租户', 'TENANT', '软删除租户'),
  ('TENANT_AUDIT_READ', '查看审计', 'TENANT', '查看租户审计时间线'),
  ('TENANT_AUDIT_EXPORT', '导出审计', 'TENANT', '导出审计 CSV'),
  ('ORG_READ', '查看组织', 'ORG', '查看组织树'),
  ('ORG_CREATE', '创建组织', 'ORG', '创建组织单元'),
  ('ORG_UPDATE', '编辑组织', 'ORG', '编辑组织单元'),
  ('ORG_MOVE', '移动组织', 'ORG', '移动组织单元'),
  ('ORG_DELETE', '删除组织', 'ORG', '删除组织单元'),
  ('ACCOUNT_READ', '查看账号', 'ACCOUNT', '查看账号目录'),
  ('ACCOUNT_CREATE', '创建账号', 'ACCOUNT', '创建账号'),
  ('ACCOUNT_UPDATE', '编辑账号', 'ACCOUNT', '编辑账号资料/角色'),
  ('ACCOUNT_STATUS', '启停账号', 'ACCOUNT', '启用/禁用/锁定账号'),
  ('ACCOUNT_PASSWORD_RESET', '重置密码', 'ACCOUNT', '管理员重置账号密码'),
  ('ACCOUNT_DELETE', '删除账号', 'ACCOUNT', '软删除账号'),
  ('ACCOUNT_UNLOCK', '解锁账号', 'ACCOUNT', '解锁被锁定的账号'),
  ('ACCOUNT_CHANGE_PASSWORD', '修改本人密码', 'ACCOUNT', '本人修改密码'),
  ('ACCOUNT_LOGIN_EVENTS_READ', '查看登录历史', 'ACCOUNT', '查看账号登录事件'),
  ('ACCOUNT_IMPORT', '导入账号', 'ACCOUNT', '批量导入组织/账号'),
  ('RATING_READ', '查看评级', 'RATING', '查看评级与历史'),
  ('RATING_ASSESS', '评估评级', 'RATING', '触发评级评估'),
  ('RATING_OVERRIDE', '覆盖评级', 'RATING', '人工覆盖评级'),
  ('APPROVAL_READ', '查看审批', 'APPROVAL', '查看审批流'),
  ('APPROVAL_DECIDE', '审批决策', 'APPROVAL', '通过/驳回审批'),
  ('APPROVAL_ESCALATE', '审批升级', 'APPROVAL', '升级审批 SLA'),
  ('SKILL_READ', '查看技能', 'SKILL', '查看技能中心'),
  ('SKILL_RUN', '运行技能', 'SKILL', '运行技能规则'),
  ('SKILL_MANAGE', '管理技能', 'SKILL', '上传/审核/启停技能'),
  ('AGENT_READ', '查看 Agent', 'AGENT', '查看 Agent 运行'),
  ('AGENT_RUN', '运行 Agent', 'AGENT', '发起 Agent 运行'),
  ('MCP_READ', '查看 MCP', 'MCP', '查看 MCP 工具与凭据'),
  ('MCP_TOOLS_CALL', '调用 MCP 工具', 'MCP', '调用 MCP 工具'),
  ('MCP_KEY_MANAGE', '管理 MCP Key', 'MCP', '创建/撤销 MCP Key'),
  ('PLATFORM_READ', '查看平台', 'PLATFORM', '查看平台总览'),
  ('PLATFORM_HEALTH', '查看平台健康', 'PLATFORM', '查看各服务健康'),
  ('PLATFORM_PURGE', '数据清理', 'PLATFORM', '触发数据生命周期清理'),
  ('RBAC_READ', '查看权限', 'RBAC', '查看角色与权限矩阵'),
  ('RBAC_MANAGE', '管理权限', 'RBAC', '维护角色与权限矩阵'),
  ('ONBOARDING_READ', '查看入驻', 'ONBOARDING', '查看自助入驻申请'),
  ('ONBOARDING_REVIEW', '审核入驻', 'ONBOARDING', '人工审核自助入驻');

-- ---------------------------------------------------------------- built-in roles
INSERT INTO roles (code, name, scope, builtin, status, description) VALUES
  ('PLATFORM_ADMIN', '平台管理员', 'PLATFORM', TRUE, 'ACTIVE', '平台全量权限'),
  ('TENANT_ADMIN', '租户管理员', 'TENANT', TRUE, 'ACTIVE', '本租户组织/账号/评级/审批'),
  ('ORG_ADMIN', '组织管理员', 'TENANT', TRUE, 'ACTIVE', '本子树组织/账号'),
  ('AUDITOR', '审计员', 'TENANT', TRUE, 'ACTIVE', '只读 + 审计 + 登录历史'),
  ('MEMBER', '成员', 'TENANT', TRUE, 'ACTIVE', '本人只读与改密');

-- PLATFORM_ADMIN owns every permission (also future-safe for new catalogue rows).
INSERT INTO role_permissions (role_code, permission_code)
  SELECT 'PLATFORM_ADMIN', code FROM permissions;

INSERT INTO role_permissions (role_code, permission_code) VALUES
  ('TENANT_ADMIN', 'TENANT_READ'),
  ('TENANT_ADMIN', 'TENANT_UPDATE'),
  ('TENANT_ADMIN', 'TENANT_SUBMIT'),
  ('TENANT_ADMIN', 'TENANT_LIFECYCLE'),
  ('TENANT_ADMIN', 'TENANT_AUDIT_READ'),
  ('TENANT_ADMIN', 'TENANT_AUDIT_EXPORT'),
  ('TENANT_ADMIN', 'ORG_READ'),
  ('TENANT_ADMIN', 'ORG_CREATE'),
  ('TENANT_ADMIN', 'ORG_UPDATE'),
  ('TENANT_ADMIN', 'ORG_MOVE'),
  ('TENANT_ADMIN', 'ORG_DELETE'),
  ('TENANT_ADMIN', 'ACCOUNT_READ'),
  ('TENANT_ADMIN', 'ACCOUNT_CREATE'),
  ('TENANT_ADMIN', 'ACCOUNT_UPDATE'),
  ('TENANT_ADMIN', 'ACCOUNT_STATUS'),
  ('TENANT_ADMIN', 'ACCOUNT_PASSWORD_RESET'),
  ('TENANT_ADMIN', 'ACCOUNT_DELETE'),
  ('TENANT_ADMIN', 'ACCOUNT_UNLOCK'),
  ('TENANT_ADMIN', 'ACCOUNT_CHANGE_PASSWORD'),
  ('TENANT_ADMIN', 'ACCOUNT_LOGIN_EVENTS_READ'),
  ('TENANT_ADMIN', 'ACCOUNT_IMPORT'),
  ('TENANT_ADMIN', 'RATING_READ'),
  ('TENANT_ADMIN', 'RATING_ASSESS'),
  ('TENANT_ADMIN', 'APPROVAL_READ'),
  ('ORG_ADMIN', 'ORG_READ'),
  ('ORG_ADMIN', 'ORG_CREATE'),
  ('ORG_ADMIN', 'ORG_UPDATE'),
  ('ORG_ADMIN', 'ORG_MOVE'),
  ('ORG_ADMIN', 'ORG_DELETE'),
  ('ORG_ADMIN', 'ACCOUNT_READ'),
  ('ORG_ADMIN', 'ACCOUNT_CREATE'),
  ('ORG_ADMIN', 'ACCOUNT_UPDATE'),
  ('ORG_ADMIN', 'ACCOUNT_STATUS'),
  ('ORG_ADMIN', 'ACCOUNT_PASSWORD_RESET'),
  ('ORG_ADMIN', 'ACCOUNT_DELETE'),
  ('ORG_ADMIN', 'ACCOUNT_UNLOCK'),
  ('ORG_ADMIN', 'ACCOUNT_LOGIN_EVENTS_READ'),
  ('ORG_ADMIN', 'RATING_READ'),
  ('AUDITOR', 'TENANT_READ'),
  ('AUDITOR', 'TENANT_AUDIT_READ'),
  ('AUDITOR', 'TENANT_AUDIT_EXPORT'),
  ('AUDITOR', 'ORG_READ'),
  ('AUDITOR', 'ACCOUNT_READ'),
  ('AUDITOR', 'ACCOUNT_LOGIN_EVENTS_READ'),
  ('AUDITOR', 'RATING_READ'),
  ('AUDITOR', 'APPROVAL_READ'),
  ('AUDITOR', 'SKILL_READ'),
  ('AUDITOR', 'AGENT_READ'),
  ('AUDITOR', 'MCP_READ'),
  ('AUDITOR', 'PLATFORM_READ'),
  ('AUDITOR', 'PLATFORM_HEALTH'),
  ('AUDITOR', 'ONBOARDING_READ'),
  ('MEMBER', 'ORG_READ'),
  ('MEMBER', 'ACCOUNT_READ'),
  ('MEMBER', 'ACCOUNT_CHANGE_PASSWORD'),
  ('MEMBER', 'ACCOUNT_LOGIN_EVENTS_READ');
