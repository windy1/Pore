package ore.permission.role

import ore.permission.role.RoleTypes.RoleType
import ore.permission.scope.{ScopeSubject, GlobalScope, Scope}

trait Role extends ScopeSubject {

  def userId: Int

  def roleType: RoleType

}