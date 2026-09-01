import type { CurrentUser, Role } from '@/types/domain'

export interface RouteAccessRule {
  path: string
  roles: Role[]
  requiredPermissions?: string[]
  anyPermissions?: string[]
}

export const landingRouteRules: RouteAccessRule[] = [
  { path: '/admin/users', roles: ['ADMIN'], anyPermissions: ['USER_MANAGE', 'ORG_MANAGE', 'PERMISSION_MANAGE'] },
  { path: '/admin/security', roles: ['ADMIN'], anyPermissions: ['AUDIT_READ', 'INTEGRITY_VERIFY', 'ALERT_MANAGE', 'GRADE_READ', 'GRADE_REVERT_SMALL', 'GRADE_REVERT_REQUEST', 'GRADE_REVERT_APPROVE', 'GRADE_RESTORE_ORIGINAL'] },
  { path: '/teacher/courses', roles: ['TEACHER'], requiredPermissions: ['COURSE_READ'] },
  { path: '/teacher/history', roles: ['TEACHER'], requiredPermissions: ['GRADE_HISTORY_READ'] },
  { path: '/student/grades', roles: ['STUDENT'], requiredPermissions: ['GRADE_SELF_READ'] },
]

export const permissionDependencies: Record<string, string[]> = {
  GRADING_SCHEME_WRITE: ['COURSE_READ', 'GRADE_READ'],
  GRADE_DRAFT_WRITE: ['COURSE_READ', 'GRADE_READ'],
  GRADE_SUBMIT: ['COURSE_READ', 'GRADE_READ'],
  GRADE_WITHDRAW: ['COURSE_READ', 'GRADE_READ'],
  GRADE_ANALYTICS_READ: ['COURSE_READ', 'GRADE_READ'],
  RISK_ANALYZE: ['COURSE_READ', 'GRADE_READ', 'GRADE_ANALYTICS_READ'],
  RISK_SELF_ANALYZE: ['GRADE_SELF_READ'],
  GRADE_REVERT_SMALL: ['GRADE_READ'],
}

export function expandPermissionDependencies(permissions: string[]): string[] {
  const expanded = new Set(permissions)
  let changed = true
  while (changed) {
    changed = false
    ;[...expanded].forEach((permission) => {
      ;(permissionDependencies[permission] || []).forEach((dependency) => {
        if (!expanded.has(dependency)) {
          expanded.add(dependency)
          changed = true
        }
      })
    })
  }
  return [...expanded].sort()
}

export function missingPermissionDependencies(permissions: string[]): string[] {
  const current = new Set(permissions)
  return expandPermissionDependencies(permissions).filter((permission) => !current.has(permission))
}

export function canAccess(
  role: Role | undefined,
  permissions: string[] = [],
  requiredRoles: Role[] = [],
  requiredPermissions: string[] = [],
  anyPermissions: string[] = [],
): boolean {
  if (!role) return false
  if (requiredRoles.length && !requiredRoles.includes(role)) return false
  if (permissions.includes('*')) return true
  return requiredPermissions.every((permission) => permissions.includes(permission))
    && (!anyPermissions.length || anyPermissions.some((permission) => permissions.includes(permission)))
}

export function firstAccessiblePath(roles: Role[], permissions: string[],
                                    rules: RouteAccessRule[] = landingRouteRules): string | null {
  return rules.find((rule) => roles.some((role) => canAccess(role, permissions, rule.roles,
    rule.requiredPermissions, rule.anyPermissions)))?.path || null
}

export function homeForUser(user: Pick<CurrentUser, 'role' | 'roles' | 'permissions'>): string | null {
  return firstAccessiblePath([...new Set([user.role, ...(user.roles || [])])], user.permissions || [])
}
