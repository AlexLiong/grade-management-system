import { describe, expect, it } from 'vitest'
import {
  canAccess, expandPermissionDependencies, firstAccessiblePath, homeForUser, missingPermissionDependencies,
} from './permission'

describe('permission checks', () => {
  it('does not let an administrator bypass assigned permissions', () => {
    expect(canAccess('ADMIN', ['AUDIT_READ'], ['ADMIN'], ['USER_MANAGE'])).toBe(false)
  })

  it('requires both role and every requested permission', () => {
    expect(canAccess('TEACHER', ['GRADE_READ', 'GRADE_SUBMIT'], ['TEACHER'], ['GRADE_READ', 'GRADE_SUBMIT'])).toBe(true)
    expect(canAccess('STUDENT', ['GRADE_SELF_READ'], ['TEACHER'], ['GRADE_SELF_READ'])).toBe(false)
  })

  it('honors an explicit wildcard grant', () => {
    expect(canAccess('ADMIN', ['*'], ['ADMIN'], ['USER_MANAGE', 'INTEGRITY_VERIFY'])).toBe(true)
  })

  it('requires at least one permission from an any-permission group', () => {
    expect(canAccess('ADMIN', ['AUDIT_READ'], ['ADMIN'], [], ['AUDIT_READ', 'ALERT_MANAGE'])).toBe(true)
    expect(canAccess('ADMIN', ['USER_MANAGE'], ['ADMIN'], [], ['AUDIT_READ', 'ALERT_MANAGE'])).toBe(false)
  })

  it('lands a security-only administrator on the first route they can actually access', () => {
    expect(homeForUser({ role: 'ADMIN', roles: ['ADMIN'], permissions: ['AUDIT_READ'] }))
      .toBe('/admin/security')
    expect(homeForUser({ role: 'ADMIN', roles: ['ADMIN'], permissions: ['GRADE_READ'] }))
      .toBe('/admin/security')
  })

  it('returns no landing page when a restricted role cannot access any work route', () => {
    expect(homeForUser({ role: 'TEACHER', roles: ['TEACHER'], permissions: ['GRADE_READ'] })).toBeNull()
    expect(firstAccessiblePath(['ADMIN'], [])).toBeNull()
  })

  it('expands transitive teacher and student permission dependencies', () => {
    expect(expandPermissionDependencies(['RISK_ANALYZE'])).toEqual([
      'COURSE_READ', 'GRADE_ANALYTICS_READ', 'GRADE_READ', 'RISK_ANALYZE',
    ])
    expect(missingPermissionDependencies(['GRADE_SUBMIT'])).toEqual(['COURSE_READ', 'GRADE_READ'])
    expect(expandPermissionDependencies(['RISK_SELF_ANALYZE'])).toEqual(['GRADE_SELF_READ', 'RISK_SELF_ANALYZE'])
    expect(expandPermissionDependencies(['GRADE_REVERT_SMALL'])).toEqual(['GRADE_READ', 'GRADE_REVERT_SMALL'])
  })
})
