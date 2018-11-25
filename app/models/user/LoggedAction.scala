package models.user

import scala.collection.immutable

import controllers.sugar.Requests.AuthRequest
import db.impl.schema.LoggedActionTable
import db.{DbRef, Model, ModelQuery, ModelService, ObjId, ObjectTimestamp}
import ore.StatTracker
import ore.user.UserOwned

import cats.effect.IO
import com.github.tminglei.slickpg.InetString
import enumeratum.values.{IntEnum, IntEnumEntry}
import slick.lifted.TableQuery

case class LoggedActionModel[Ctx](
    id: ObjId[LoggedActionModel[Ctx]] = ObjId.Uninitialized[LoggedActionModel[Ctx]](),
    createdAt: ObjectTimestamp = ObjectTimestamp.Uninitialized,
    userId: DbRef[User],
    address: InetString,
    action: LoggedAction[Ctx],
    actionContext: LoggedActionContext[Ctx],
    actionContextId: DbRef[Ctx],
    newState: String,
    oldState: String
) extends Model {

  override type T = LoggedActionTable[Ctx]
  override type M = LoggedActionModel[Ctx]
}
object LoggedActionModel {
  implicit def query[Ctx]: ModelQuery[LoggedActionModel[Ctx]] =
    ModelQuery.from[LoggedActionModel[Ctx]](
      TableQuery[LoggedActionTable[Ctx]],
      (obj, _, time) => obj.copy(createdAt = time)
    )

  implicit val isUserOwned: UserOwned[LoggedActionModel[_]] = (a: LoggedActionModel[_]) => a.userId
}

sealed abstract class LoggedActionContext[Ctx](val value: Int) extends IntEnumEntry

object LoggedActionContext extends IntEnum[LoggedActionContext[_]] {

  case object Project      extends LoggedActionContext[models.project.Project](0)
  case object Version      extends LoggedActionContext[models.project.Version](1)
  case object ProjectPage  extends LoggedActionContext[models.project.Page](2)
  case object User         extends LoggedActionContext[models.user.User](3)
  case object Organization extends LoggedActionContext[models.user.Organization](4)

  val values: immutable.IndexedSeq[LoggedActionContext[_]] = findValues

}

sealed abstract class LoggedAction[Ctx](
    val value: Int,
    val name: String,
    val context: LoggedActionContext[Ctx],
    val description: String
) extends IntEnumEntry

case object LoggedAction extends IntEnum[LoggedAction[_]] {

  case object ProjectVisibilityChange
      extends LoggedAction(
        0,
        "ProjectVisibilityChange",
        LoggedActionContext.Project,
        "The project visibility state was changed"
      )
  case object ProjectCreated
      extends LoggedAction(1, "ProjectCreated", LoggedActionContext.Project, "The project was created")
  case object ProjectRenamed
      extends LoggedAction(2, "ProjectRename", LoggedActionContext.Project, "The project was renamed")
  case object ProjectFlagged
      extends LoggedAction(3, "ProjectFlagged", LoggedActionContext.Project, "The project got flagged")
  case object ProjectSettingsChanged
      extends LoggedAction(
        4,
        "ProjectSettingsChanged",
        LoggedActionContext.Project,
        "The project settings were changed"
      )
  case object ProjectMemberRemoved
      extends LoggedAction(
        5,
        "ProjectMemberRemoved",
        LoggedActionContext.Project,
        "A Member was removed from the project"
      )
  case object ProjectIconChanged
      extends LoggedAction(6, "ProjectIconChanged", LoggedActionContext.Project, "The project icon was changed")
  case object ProjectPageEdited
      extends LoggedAction(7, "ProjectPageEdited", LoggedActionContext.ProjectPage, "A project page got edited")
  case object ProjectFlagResolved
      extends LoggedAction(13, "ProjectFlagResolved", LoggedActionContext.Project, "The flag was resolved")

  case object VersionDeleted
      extends LoggedAction(8, "VersionDeleted", LoggedActionContext.Version, "The version was deleted")
  case object VersionUploaded
      extends LoggedAction(9, "VersionUploaded", LoggedActionContext.Version, "A new version was uploaded")
  case object VersionAsRecommended
      extends LoggedAction(
        11,
        "VersionAsRecommended",
        LoggedActionContext.Version,
        "The version was set as recommended version"
      )
  case object VersionDescriptionEdited
      extends LoggedAction(
        12,
        "VersionDescriptionEdited",
        LoggedActionContext.Version,
        "The version description was edited"
      )
  case object VersionReviewStateChanged
      extends LoggedAction(
        17,
        "VersionReviewStateChanged",
        LoggedActionContext.Version,
        "If the review state changed"
      )

  case object UserTaglineChanged
      extends LoggedAction(14, "UserTaglineChanged", LoggedActionContext.User, "The user tagline changed")
  case object UserPgpKeySaved
      extends LoggedAction(15, "UserPgpKeySaved", LoggedActionContext.User, "The user saved a PGP Public Key")
  case object UserPgpKeyRemoved
      extends LoggedAction(16, "UserPgpKeyRemoved", LoggedActionContext.User, "The user removed a PGP Public Key")
  val values: immutable.IndexedSeq[LoggedAction[_]] = findValues
}

object UserActionLogger {

  def log[Ctx](
      request: AuthRequest[_],
      action: LoggedAction[Ctx],
      actionContextId: DbRef[Ctx],
      newState: String,
      oldState: String
  )(implicit service: ModelService): IO[LoggedActionModel[Ctx]] =
    service.insert(
      LoggedActionModel(
        ObjId.Uninitialized[LoggedActionModel[Ctx]](),
        ObjectTimestamp.Uninitialized,
        request.user.id.value,
        InetString(StatTracker.remoteAddress(request)),
        action,
        action.context,
        actionContextId,
        newState,
        oldState
      )
    )

}
