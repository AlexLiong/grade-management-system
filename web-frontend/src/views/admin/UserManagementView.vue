<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Building2, Pencil, Plus, Search, ShieldCheck, UsersRound, X } from 'lucide-vue-next'
import PageState from '@/components/PageState.vue'
import { adminApi } from '@/api/services'
import { toAppError } from '@/api/http'
import type { ManagedUser, Organization, PageResult, PermissionOption, Role, RolePermissions } from '@/types/domain'
import { useAuthStore } from '@/stores/auth'
import {
  expandPermissionDependencies, missingPermissionDependencies, permissionDependencies,
} from '@/utils/permission'

type UserForm = Partial<ManagedUser> & {
  password?: string
  newPassword?: string
  studentNo?: string
  teacherNo?: string
  className?: string
  major?: string
}
const auth = useAuthStore()
const canManageUsers = computed(() => auth.hasPermission('USER_MANAGE'))
const canManageOrganizations = computed(() => auth.hasPermission('ORG_MANAGE'))
const canManagePermissions = computed(() => auth.hasPermission('PERMISSION_MANAGE'))
const activeTab = ref(canManageUsers.value ? 'users' : canManageOrganizations.value ? 'organizations' : 'roles')
const loading = ref(false)
const error = ref('')
const users = ref<ManagedUser[]>([])
const organizations = ref<Organization[]>([])
const permissionOptions = ref<PermissionOption[]>([])
const rolePermissionRows = ref<RolePermissions[]>([])
const total = ref(0)
const filters = reactive({ keyword: '', role: '', organizationId: '', page: 1, size: 15 })
const userDialog = ref(false)
const userSaving = ref(false)
const userForm = reactive<UserForm>({})
const organizationDialog = ref(false)
const organizationSaving = ref(false)
const organizationForm = reactive<Partial<Organization>>({})
const roleDialog = ref(false)
const roleSaving = ref(false)
const roleForm = reactive<{ roleId: string; roleCode: Role; permissions: string[] }>({
  roleId: '', roleCode: 'STUDENT', permissions: [],
})

const roleLabels: Record<Role, string> = { TEACHER: '教师', STUDENT: '学生', ADMIN: '管理员' }
const roleTag: Record<Role, 'success' | 'warning' | 'danger'> = { TEACHER: 'success', STUDENT: 'warning', ADMIN: 'danger' }
function roleLabel(role: Role) { return roleLabels[role] || role }
function roleType(role: Role) { return roleTag[role] || 'warning' }
function userStatusLabel(status?: ManagedUser['status']) {
  return status === 'ACTIVE' ? '启用' : status === 'LOCKED' ? '锁定' : '停用'
}
function userStatusType(status?: ManagedUser['status']) {
  return status === 'ACTIVE' ? 'success' : status === 'LOCKED' ? 'danger' : 'info'
}

async function loadUsers() {
  loading.value = true
  error.value = ''
  try {
    const result: PageResult<ManagedUser> = await adminApi.users({
      ...filters, keyword: filters.keyword.trim() || undefined,
      includeRolePermissions: canManagePermissions.value,
    })
    users.value = result.items || []
    total.value = result.total || 0
  } catch (cause) { error.value = toAppError(cause).message }
  finally { loading.value = false }
}

async function loadMetadata() {
  const results = await Promise.allSettled([
    canManageOrganizations.value ? adminApi.organizations() : Promise.resolve([]),
    auth.hasPermission('PERMISSION_MANAGE') ? adminApi.permissions() : Promise.resolve([]),
    auth.hasPermission('PERMISSION_MANAGE') ? adminApi.roles() : Promise.resolve([]),
  ])
  if (results[0].status === 'fulfilled') organizations.value = results[0].value || []
  if (results[1].status === 'fulfilled') permissionOptions.value = results[1].value || []
  if (results[2].status === 'fulfilled') rolePermissionRows.value = results[2].value || []
}

function resetFilters() {
  Object.assign(filters, { keyword: '', role: '', organizationId: '', page: 1 })
  loadUsers()
}

function openUser(user?: ManagedUser) {
  Object.keys(userForm).forEach((key) => delete userForm[key as keyof UserForm])
  Object.assign(userForm, user ? {
    ...user,
    roles: [...(user.roles || [user.role])],
    permissions: [...(user.permissions || [])],
    permissionOverrides: { ...(user.permissionOverrides || {}) },
  } : {
    role: 'STUDENT', roles: [], status: 'ACTIVE', enabled: true, permissions: [], permissionOverrides: {},
    organizationId: '', password: '',
  })
  userDialog.value = true
}

type OverrideMode = 'INHERIT' | 'ALLOW' | 'DENY'
function overrideMode(code: string): OverrideMode {
  if (!userForm.permissionOverrides || !(code in userForm.permissionOverrides)) return 'INHERIT'
  return userForm.permissionOverrides[code] ? 'ALLOW' : 'DENY'
}

function setOverrideMode(code: string, mode: string) {
  if (!userForm.permissionOverrides) userForm.permissionOverrides = {}
  if (mode === 'INHERIT') delete userForm.permissionOverrides[code]
  else userForm.permissionOverrides[code] = mode === 'ALLOW'
  if (mode === 'ALLOW') {
    expandPermissionDependencies([code]).filter((item) => item !== code).forEach((dependency) => {
      if (roleDefaultAllows(dependency)) delete userForm.permissionOverrides?.[dependency]
      else if (userForm.permissionOverrides) userForm.permissionOverrides[dependency] = true
    })
  }
  if (mode === 'DENY') {
    permissionOptions.value.filter((item) => expandPermissionDependencies([item.code]).includes(code))
      .forEach((dependent) => {
        if (dependent.code !== code && userForm.permissionOverrides) {
          userForm.permissionOverrides[dependent.code] = false
        }
      })
  }
}

function permissionDependencyLabel(code: string) {
  const dependencies = permissionDependencies[code] || []
  return dependencies.length ? `需同时具备 ${dependencies.join('、')}` : ''
}

function roleDefaultAllows(code: string) {
  const roles = userForm.roles?.length ? userForm.roles : userForm.role ? [userForm.role] : []
  return rolePermissionRows.value.some((role) => roles.includes(role.roleCode) && role.permissions.includes(code))
}

async function saveUser() {
  if (!userForm.username?.trim() || !userForm.displayName?.trim() || !userForm.role) {
    ElMessage.warning('请完整填写账号、姓名和角色')
    return
  }
  if (!userForm.id && (!userForm.password || userForm.password.length < 8)) {
    ElMessage.warning('新用户初始密码至少 8 位')
    return
  }
  if (!userForm.id && userForm.role === 'STUDENT' && !userForm.studentNo?.trim()) {
    ElMessage.warning('学生账号必须填写学号')
    return
  }
  if (userForm.role === 'TEACHER' && (!userForm.id
    ? (!userForm.teacherNo?.trim() || !userForm.organizationId)
    : (canManageOrganizations.value && !userForm.organizationId))) {
    ElMessage.warning('教师账号必须填写工号并选择所属组织')
    return
  }
  const effective = new Set(permissionOptions.value.filter((item) => roleDefaultAllows(item.code))
    .map((item) => item.code))
  Object.entries(userForm.permissionOverrides || {}).forEach(([code, allowed]) => {
    if (allowed) effective.add(code)
    else effective.delete(code)
  })
  const missingDependencies = missingPermissionDependencies([...effective])
  if (missingDependencies.length) {
    ElMessage.warning(`权限依赖不完整，请补充：${missingDependencies.join('、')}`)
    return
  }
  userSaving.value = true
  try {
    const mutation = { ...userForm, username: userForm.username.trim(), displayName: userForm.displayName.trim() }
    if (mutation.id && !canManageOrganizations.value) delete mutation.organizationId
    if (!canManagePermissions.value) delete mutation.permissionOverrides
    await adminApi.saveUser(mutation)
    ElMessage.success(userForm.id ? '用户信息已更新' : '用户已创建')
    userDialog.value = false
    await loadUsers()
  } catch (cause) { ElMessage.error(toAppError(cause).message) }
  finally { userSaving.value = false }
}

function openRolePermissions(role: RolePermissions) {
  Object.assign(roleForm, { roleId: role.roleId, roleCode: role.roleCode, permissions: [...role.permissions] })
  roleDialog.value = true
}

async function saveRolePermissions() {
  roleSaving.value = true
  try {
    const expanded = expandPermissionDependencies(roleForm.permissions)
    if (expanded.length !== roleForm.permissions.length) {
      ElMessage.info('已自动补齐所选功能的读取权限依赖')
      roleForm.permissions = expanded
    }
    const updated = await adminApi.updateRolePermissions(roleForm.roleId, roleForm.permissions)
    const index = rolePermissionRows.value.findIndex((item) => item.roleId === updated.roleId)
    if (index >= 0) rolePermissionRows.value[index] = updated
    ElMessage.success('角色默认权限已更新')
    roleDialog.value = false
    await loadUsers()
  } catch (cause) { ElMessage.error(toAppError(cause).message) }
  finally { roleSaving.value = false }
}

function openOrganization(organization?: Organization) {
  Object.keys(organizationForm).forEach((key) => delete organizationForm[key as keyof Organization])
  Object.assign(organizationForm, organization ? { ...organization } : { code: '', name: '', parentId: null })
  organizationDialog.value = true
}

async function saveOrganization() {
  if (!organizationForm.code?.trim() || !organizationForm.name?.trim()) { ElMessage.warning('请输入组织编码和名称'); return }
  organizationSaving.value = true
  try {
    await adminApi.saveOrganization({ ...organizationForm, name: organizationForm.name.trim() })
    ElMessage.success(organizationForm.id ? '组织已更新' : '组织已创建')
    organizationDialog.value = false
    await loadMetadata()
  } catch (cause) { ElMessage.error(toAppError(cause).message) }
  finally { organizationSaving.value = false }
}

async function removeOrganization(organization: Organization) {
  try {
    await ElMessageBox.confirm(`确定删除组织“${organization.name}”吗？存在关联用户时后端会拒绝此操作。`, '删除组织', {
      confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning',
    })
    await adminApi.deleteOrganization(organization.id)
    ElMessage.success('组织已删除')
    await loadMetadata()
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(toAppError(cause).message)
  }
}

onMounted(() => {
  if (canManageUsers.value) loadUsers()
  loadMetadata()
})
</script>

<template>
  <div class="page-stack">
    <header class="page-header"><div><span class="eyebrow">管理员工作台</span><h1>用户、组织与权限</h1><p>维护三类用户的组织归属、账号状态和最小访问权限。</p></div><div class="page-actions"><el-button v-if="activeTab === 'users' && canManageUsers && canManagePermissions" type="primary" :icon="Plus" @click="openUser()">新增用户</el-button><el-button v-else-if="activeTab === 'organizations' && auth.hasPermission('ORG_MANAGE')" type="primary" :icon="Plus" @click="openOrganization()">新增组织</el-button></div></header>

    <section class="surface admin-tabs">
      <el-tabs v-model="activeTab">
        <el-tab-pane v-if="canManageUsers" name="users"><template #label><span class="tab-label"><UsersRound :size="16" />用户与权限</span></template>
          <div class="admin-pane">
            <div class="filter-bar user-filter">
              <el-input v-model="filters.keyword" clearable placeholder="账号或姓名" :prefix-icon="Search" @keyup.enter="filters.page = 1; loadUsers()" />
              <el-select v-model="filters.role" clearable placeholder="全部角色" @change="filters.page = 1; loadUsers()"><el-option v-for="(label, value) in roleLabels" :key="value" :label="label" :value="value" /></el-select>
              <el-select v-if="canManageOrganizations" v-model="filters.organizationId" clearable filterable placeholder="全部组织" @change="filters.page = 1; loadUsers()"><el-option v-for="organization in organizations" :key="organization.id" :label="organization.name" :value="organization.id" /></el-select>
              <el-button type="primary" :icon="Search" @click="filters.page = 1; loadUsers()">查询</el-button>
              <el-tooltip content="清空筛选"><el-button circle :icon="X" aria-label="清空筛选" @click="resetFilters" /></el-tooltip>
            </div>
            <PageState :loading="loading" :error="error" :empty="!users.length" empty-title="暂无用户" @retry="loadUsers">
              <el-table :data="users" stripe>
                <el-table-column prop="username" label="账号" min-width="130" /><el-table-column prop="displayName" label="姓名" min-width="110"><template #default="scope"><strong>{{ scope.row.displayName }}</strong></template></el-table-column>
                <el-table-column label="角色" width="95"><template #default="scope"><el-tag :type="roleType(scope.row.role)" effect="plain">{{ roleLabel(scope.row.role) }}</el-tag></template></el-table-column>
                <el-table-column v-if="canManageOrganizations" prop="organizationName" label="所属组织" min-width="160"><template #default="scope">{{ scope.row.organizationName || '未关联' }}</template></el-table-column>
                <el-table-column v-if="canManagePermissions" label="个人权限覆盖" min-width="150"><template #default="scope"><span class="permission-summary">{{ Object.keys(scope.row.permissionOverrides || {}).length ? `${Object.keys(scope.row.permissionOverrides).length} 项` : '继承角色默认值' }}</span></template></el-table-column>
                <el-table-column label="状态" width="90"><template #default="scope"><el-tag :type="userStatusType(scope.row.status)" effect="plain">{{ userStatusLabel(scope.row.status) }}</el-tag></template></el-table-column>
                <el-table-column prop="updatedAt" label="更新时间" min-width="165"><template #default="scope">{{ scope.row.updatedAt || '-' }}</template></el-table-column>
                <el-table-column label="操作" width="105" fixed="right"><template #default="scope"><el-button v-if="canManageUsers && (!scope.row.roles?.includes('ADMIN') || canManagePermissions)" link type="primary" :icon="Pencil" @click="openUser(scope.row)">编辑</el-button><span v-else class="muted-text">只读</span></template></el-table-column>
              </el-table>
              <div v-if="total > filters.size" class="pagination-row"><el-pagination v-model:current-page="filters.page" :page-size="filters.size" :total="total" layout="total, prev, pager, next" @current-change="loadUsers" /></div>
            </PageState>
          </div>
        </el-tab-pane>
        <el-tab-pane v-if="canManageOrganizations" name="organizations"><template #label><span class="tab-label"><Building2 :size="16" />组织库</span></template>
          <div class="admin-pane">
            <div class="section-heading flat-heading"><div><Building2 :size="18" /><h2>组织信息</h2></div><span>{{ organizations.length }} 个组织</span></div>
            <PageState :empty="!organizations.length" empty-title="暂无组织">
              <el-table :data="organizations" row-key="id" default-expand-all>
                <el-table-column prop="name" label="组织名称" min-width="200"><template #default="scope"><strong>{{ scope.row.name }}</strong></template></el-table-column>
                <el-table-column prop="code" label="组织编码" min-width="140" /><el-table-column prop="id" label="组织编号" min-width="180" />
                <el-table-column label="操作" width="180" fixed="right"><template #default="scope"><template v-if="auth.hasPermission('ORG_MANAGE')"><el-button link type="primary" :icon="Pencil" @click="openOrganization(scope.row)">编辑</el-button><el-button link type="danger" @click="removeOrganization(scope.row)">删除</el-button></template><span v-else class="muted-text">只读</span></template></el-table-column>
              </el-table>
            </PageState>
          </div>
        </el-tab-pane>
        <el-tab-pane v-if="canManagePermissions" name="roles"><template #label><span class="tab-label"><ShieldCheck :size="16" />角色默认权限</span></template>
          <div class="admin-pane">
            <div class="section-heading flat-heading"><div><ShieldCheck :size="18" /><h2>角色权限</h2></div><span>{{ rolePermissionRows.length }} 个可分配角色</span></div>
            <PageState :empty="!rolePermissionRows.length" empty-title="暂无角色权限">
              <el-table :data="rolePermissionRows" row-key="roleId">
                <el-table-column label="角色" min-width="140"><template #default="scope"><el-tag :type="roleType(scope.row.roleCode)" effect="plain">{{ roleLabel(scope.row.roleCode) }}</el-tag></template></el-table-column>
                <el-table-column label="默认权限" min-width="420"><template #default="scope"><div class="role-permission-tags"><el-tag v-for="code in scope.row.permissions" :key="code" size="small" effect="plain">{{ code }}</el-tag><span v-if="!scope.row.permissions.length" class="muted-text">无默认权限</span></div></template></el-table-column>
                <el-table-column label="操作" width="110" fixed="right"><template #default="scope"><el-button link type="primary" :icon="Pencil" @click="openRolePermissions(scope.row)">编辑</el-button></template></el-table-column>
              </el-table>
            </PageState>
          </div>
        </el-tab-pane>
      </el-tabs>
    </section>

    <el-dialog v-model="userDialog" :title="userForm.id ? '编辑用户' : '新增用户'" width="min(680px, 94vw)" destroy-on-close>
      <el-form label-position="top" class="dialog-form-grid">
        <el-form-item label="账号" required><el-input v-model="userForm.username" :disabled="Boolean(userForm.id)" maxlength="40" /></el-form-item>
        <el-form-item label="姓名" required><el-input v-model="userForm.displayName" maxlength="40" /></el-form-item>
        <el-form-item label="电子邮箱"><el-input v-model="userForm.email" type="email" maxlength="120" /></el-form-item>
        <el-form-item label="角色" required><el-select v-model="userForm.role" :disabled="Boolean(userForm.id) || !canManagePermissions"><el-option v-for="(label, value) in roleLabels" :key="value" :label="label" :value="value" :disabled="value === 'TEACHER' && !canManageOrganizations" /></el-select></el-form-item>
        <el-form-item v-if="userForm.role === 'TEACHER' && (!userForm.id || canManageOrganizations)" label="所属组织" required><el-select v-model="userForm.organizationId" filterable><el-option v-for="organization in organizations" :key="organization.id" :label="organization.name" :value="organization.id" /></el-select></el-form-item>
        <el-form-item v-if="!userForm.id" label="初始密码" required><el-input v-model="userForm.password" type="password" show-password autocomplete="new-password" /></el-form-item>
        <el-form-item v-else-if="!userForm.roles?.includes('ADMIN') || canManagePermissions" label="重置密码"><el-input v-model="userForm.newPassword" type="password" show-password autocomplete="new-password" placeholder="不修改请留空" /></el-form-item>
        <el-form-item v-if="userForm.role === 'STUDENT'" :label="userForm.id ? '学号（固定标识）' : '学号'" required><el-input v-model="userForm.studentNo" :disabled="Boolean(userForm.id)" maxlength="32" /></el-form-item>
        <el-form-item v-if="userForm.role === 'STUDENT'" label="班级"><el-input v-model="userForm.className" maxlength="80" /></el-form-item>
        <el-form-item v-if="userForm.role === 'STUDENT'" label="专业"><el-input v-model="userForm.major" maxlength="80" /></el-form-item>
        <el-form-item v-if="userForm.role === 'TEACHER'" :label="userForm.id ? '工号（固定标识）' : '工号'" required><el-input v-model="userForm.teacherNo" :disabled="Boolean(userForm.id)" maxlength="32" /></el-form-item>
        <el-form-item v-if="userForm.id" label="账号状态"><el-select v-model="userForm.status" :disabled="Boolean(userForm.roles?.includes('ADMIN') && !canManagePermissions)"><el-option label="启用" value="ACTIVE" /><el-option label="停用" value="DISABLED" /><el-option label="锁定" value="LOCKED" /></el-select></el-form-item>
        <el-form-item v-if="canManagePermissions" label="个人权限覆盖" class="full-field"><div class="permission-override-list"><div v-for="item in permissionOptions" :key="item.code" class="permission-override-row"><div><strong>{{ item.name }}</strong><small>{{ item.code }} · 角色默认{{ roleDefaultAllows(item.code) ? '允许' : '拒绝' }}</small><small v-if="permissionDependencyLabel(item.code)">{{ permissionDependencyLabel(item.code) }}</small></div><el-select :model-value="overrideMode(item.code)" class="override-select" @update:model-value="setOverrideMode(item.code, $event)"><el-option label="继承" value="INHERIT" /><el-option label="单独允许" value="ALLOW" /><el-option label="单独拒绝" value="DENY" /></el-select></div></div><el-empty v-if="!permissionOptions.length" description="暂无可分配权限" :image-size="48" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="userDialog = false">取消</el-button><el-button type="primary" :icon="ShieldCheck" :loading="userSaving" @click="saveUser">保存用户与权限</el-button></template>
    </el-dialog>

    <el-dialog v-model="organizationDialog" :title="organizationForm.id ? '编辑组织' : '新增组织'" width="min(520px, 94vw)" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="组织编码" required><el-input v-model="organizationForm.code" maxlength="24" placeholder="例如 SE_COLLEGE" @input="organizationForm.code = organizationForm.code?.toUpperCase()" /></el-form-item>
        <el-form-item label="组织名称" required><el-input v-model="organizationForm.name" maxlength="80" /></el-form-item>
        <el-form-item label="上级组织"><el-select v-model="organizationForm.parentId" clearable filterable><el-option v-for="item in organizations.filter((entry) => entry.id !== organizationForm.id)" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="organizationDialog = false">取消</el-button><el-button type="primary" :icon="Building2" :loading="organizationSaving" @click="saveOrganization">保存组织</el-button></template>
    </el-dialog>

    <el-dialog v-model="roleDialog" :title="`${roleLabel(roleForm.roleCode)}默认权限`" width="min(720px, 94vw)" destroy-on-close>
      <el-checkbox-group v-model="roleForm.permissions" class="permission-grid">
        <el-checkbox v-for="item in permissionOptions" :key="item.code" :value="item.code"><span>{{ item.name }}</span><small>{{ item.code }}</small></el-checkbox>
      </el-checkbox-group>
      <el-empty v-if="!permissionOptions.length" description="暂无可分配权限" :image-size="48" />
      <template #footer><el-button @click="roleDialog = false">取消</el-button><el-button type="primary" :icon="ShieldCheck" :loading="roleSaving" @click="saveRolePermissions">保存默认权限</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.permission-override-list { width: 100%; display: grid; gap: 6px; }
.permission-override-row { min-width: 0; display: grid; grid-template-columns: minmax(0, 1fr) 124px; align-items: center; gap: 12px; padding: 7px 0; border-bottom: 1px solid var(--line); }
.permission-override-row:last-child { border-bottom: 0; }
.permission-override-row strong, .permission-override-row small { display: block; }
.permission-override-row strong { font-size: 13px; }
.permission-override-row small { margin-top: 2px; color: var(--muted); font-size: 10px; overflow-wrap: anywhere; }
.override-select { width: 124px; }
.role-permission-tags { display: flex; flex-wrap: wrap; gap: 5px; }
@media (max-width: 560px) {
  .permission-override-row { grid-template-columns: 1fr; }
  .override-select { width: 100%; }
}
</style>
