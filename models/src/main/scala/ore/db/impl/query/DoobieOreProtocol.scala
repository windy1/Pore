package ore.db.impl.query

import java.net.InetAddress
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit

import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration
import scala.reflect.runtime.universe.TypeTag

import ore.data.project.{Category, FlagReason}
import ore.data.user.notification.NotificationType
import ore.data.{Color, DownloadType, Prompt}
import ore.db.{DbRef, Model, ObjId, ObjInstant}
import ore.models.api.ApiKey
import ore.models.project.{ReviewState, TagColor, Visibility}
import ore.models.user.{LoggedAction, LoggedActionContext, User}
import ore.permission.Permission
import ore.permission.role.{Role, RoleCategory}

import cats.data.{NonEmptyList => NEL}
import com.github.tminglei.slickpg.InetString
import com.typesafe.scalalogging
import doobie._
import doobie.enum.JdbcType
import doobie.implicits._
import doobie.postgres.implicits._
import enumeratum.values._
import org.postgresql.util.{PGInterval, PGobject}
import shapeless._

trait DoobieOreProtocol {

  //implicit val logger = createLogger("Database")

  def fragParens(frag: Fragment): Fragment = fr"(" ++ frag ++ fr")"

  def createLogger(name: String): LogHandler = {
    val logger = scalalogging.Logger(name)

    LogHandler {
      case util.log.Success(sql, args, exec, processing) =>
        logger.info(
          s"""|Successful Statement Execution:
              |
              |  ${sql.lines.dropWhile(_.trim.isEmpty).mkString("\n  ")}
              |
              | arguments = [${args.mkString(", ")}]
              |   elapsed = ${exec.toMillis} ms exec + ${processing.toMillis} ms processing (${(exec + processing).toMillis} ms total)""".stripMargin
        )
      case util.log.ProcessingFailure(sql, args, exec, processing, failure) =>
        logger.error(
          s"""|Failed Resultset Processing:
              |
              |  ${sql.lines.dropWhile(_.trim.isEmpty).mkString("\n  ")}
              |
              | arguments = [${args.mkString(", ")}]
              |   elapsed = ${exec.toMillis} ms exec + ${processing.toMillis} ms processing (failed) (${(exec + processing).toMillis} ms total)
              |   failure = ${failure.getMessage}""".stripMargin,
          failure
        )
      case util.log.ExecFailure(sql, args, exec, failure) =>
        logger.error(
          s"""Failed Statement Execution:
             |
             |  ${sql.lines.dropWhile(_.trim.isEmpty).mkString("\n  ")}
             |
             | arguments = [${args.mkString(", ")}]
             |   elapsed = ${exec.toMillis} ms exec (failed)
             |   failure = ${failure.getMessage}""".stripMargin,
          failure
        )
    }
  }

  implicit def objectIdMeta[A](implicit tt: TypeTag[ObjId[A]]): Meta[ObjId[A]] =
    Meta[Long].timap(ObjId.apply[A])(_.value)
  implicit val objInstantMeta: Meta[ObjInstant] = Meta[Instant].timap(ObjInstant.apply)(_.value)

  implicit def modelRead[A](implicit raw: Read[(ObjId[A], ObjInstant, A)]): Read[Model[A]] = raw.map {
    case (id, time, obj) => Model(id, time, obj)
  }
  implicit def modelWrite[A](implicit raw: Write[(ObjId[A], ObjInstant, A)]): Write[Model[A]] = raw.contramap {
    case Model(id, createdAt, obj) => (id, createdAt, obj)
  }

  implicit val intervalMeta: Meta[PGInterval] = Meta.Advanced.other[PGInterval]("interval")
  implicit val finiteDurationPut: Put[FiniteDuration] = intervalMeta.put.contramap[FiniteDuration] { a =>
    Option(a).map { dur =>
      @tailrec
      def getTimeStr(dur: FiniteDuration): String = {
        if (dur.length > Int.MaxValue || dur.length < Int.MinValue) {
          val nextUnit = TimeUnit.values()(dur.unit.ordinal() + 1)
          getTimeStr(FiniteDuration(nextUnit.convert(dur.length, dur.unit), nextUnit))
        } else {
          val length = dur.length
          dur.unit match {
            case TimeUnit.DAYS                                => s"$length days"
            case TimeUnit.HOURS                               => s"$length hours"
            case TimeUnit.MINUTES                             => s"$length minutes"
            case TimeUnit.SECONDS                             => s"$length seconds"
            case TimeUnit.MILLISECONDS                        => s"$length milliseconds"
            case TimeUnit.MICROSECONDS | TimeUnit.NANOSECONDS => s"$length microseconds"
          }
        }
      }

      new PGInterval(getTimeStr(dur))
    }.orNull
  }

  def enumeratumMeta[V: TypeTag, E <: ValueEnumEntry[V]: TypeTag](
      enum: ValueEnum[V, E]
  )(implicit meta: Meta[V]): Meta[E] =
    meta.timap[E](enum.withValue)(_.value)

  implicit val colorMeta: Meta[Color]                        = enumeratumMeta(Color)
  implicit val tagColorMeta: Meta[TagColor]                  = enumeratumMeta(TagColor)
  implicit val roleTypeMeta: Meta[Role]                      = enumeratumMeta(Role)
  implicit val categoryMeta: Meta[Category]                  = enumeratumMeta(Category)
  implicit val flagReasonMeta: Meta[FlagReason]              = enumeratumMeta(FlagReason)
  implicit val notificationTypeMeta: Meta[NotificationType]  = enumeratumMeta(NotificationType)
  implicit val promptMeta: Meta[Prompt]                      = enumeratumMeta(Prompt)
  implicit val downloadTypeMeta: Meta[DownloadType]          = enumeratumMeta(DownloadType)
  implicit val visibilityMeta: Meta[Visibility]              = enumeratumMeta(Visibility)
  implicit def loggedActionMeta[Ctx]: Meta[LoggedAction[Ctx]] =
    enumeratumMeta(LoggedAction).asInstanceOf[Meta[LoggedAction[Ctx]]] // scalafix:ok
  implicit def loggedActionContextMeta[Ctx]: Meta[LoggedActionContext[Ctx]] =
    enumeratumMeta(LoggedActionContext).asInstanceOf[Meta[LoggedActionContext[Ctx]]] // scalafix:ok
  implicit val reviewStateMeta: Meta[ReviewState] = enumeratumMeta(ReviewState)

  implicit val langMeta: Meta[Locale] = Meta[String].timap(Locale.forLanguageTag)(_.toLanguageTag)
  implicit val inetStringMeta: Meta[InetString] =
    Meta[InetAddress].timap(address => InetString(address.toString))(str => InetAddress.getByName(str.value))

  implicit val permissionMeta: Meta[Permission] =
    Meta.Advanced.one[Permission](
      JdbcType.Bit,
      NEL.one("bit"),
      (r, i) => {
        val s = r.getString(i)
        if (s == null) null.asInstanceOf[Permission]
        else Permission.fromBinString(s).get
      },
      (p, i, a) => {
        val obj = new PGobject
        obj.setType("bit")
        obj.setValue(a.toBinString)
        p.setObject(i, obj)
      },
      (p, i, a) => {
        val obj = new PGobject
        obj.setType("bit")
        obj.setValue(a.toBinString)
        p.updateObject(i, obj)
      }
    )

  implicit val roleCategoryMeta: Meta[RoleCategory] = pgEnumString[RoleCategory](
    name = "ROLE_CATEGORY",
    f = {
      case "global"       => RoleCategory.Global
      case "project"      => RoleCategory.Project
      case "organization" => RoleCategory.Organization
    },
    g = {
      case RoleCategory.Global       => "global"
      case RoleCategory.Project      => "project"
      case RoleCategory.Organization => "organization"
    }
  )

  def metaFromGetPut[A](implicit get: Get[A], put: Put[A]): Meta[A] = new Meta(get, put)

  implicit val promptArrayMeta: Meta[List[Prompt]] =
    metaFromGetPut[List[Int]].timap(_.map(Prompt.withValue))(_.map(_.value))
  implicit val roleTypeArrayMeta: Meta[List[Role]] =
    metaFromGetPut[List[String]].timap(_.map(Role.withValue))(_.map(_.value))

  implicit val tagColorArrayMeta: Meta[List[TagColor]] =
    Meta[Array[Int]].timap(_.toList.map(TagColor.withValue))(_.map(_.value).toArray)

  implicit def unsafeNelMeta[A](implicit listMeta: Meta[List[A]], typeTag: TypeTag[NEL[A]]): Meta[NEL[A]] =
    listMeta.timap(NEL.fromListUnsafe)(_.toList)

  implicit def unsafeNelGet[A](implicit listGet: Get[List[A]], typeTag: TypeTag[NEL[A]]): Get[NEL[A]] =
    listGet.tmap(NEL.fromListUnsafe)

  implicit def unsafeNelPut[A](implicit listPut: Put[List[A]], typeTag: TypeTag[NEL[A]]): Put[NEL[A]] =
    listPut.tcontramap(_.toList)

  implicit val userModelRead: Read[Model[User]] =
    Read[ObjId[User] :: ObjInstant :: Option[String] :: String :: Option[String] :: Option[String] :: Option[
      Instant
    ] :: List[Prompt] :: Boolean :: Option[Locale] :: HNil].map {
      case id :: createdAt :: fullName :: name :: email :: tagline :: joinDate :: readPrompts :: isLocked :: lang :: HNil =>
        Model(
          id,
          createdAt,
          User(
            id,
            fullName,
            name,
            email,
            tagline,
            joinDate,
            readPrompts,
            isLocked,
            lang
          )
        )
    }

  implicit val userModelOptRead: Read[Option[Model[User]]] =
    Read[Option[ObjId[User]] :: Option[ObjInstant] :: Option[String] :: Option[String] :: Option[String] :: Option[
      String
    ] :: Option[Instant] :: Option[List[Prompt]] :: Option[Boolean] :: Option[
      Locale
    ] :: HNil].map {
      case Some(id) :: Some(createdAt) :: fullName :: Some(name) :: email :: tagline :: joinDate :: Some(readPrompts) :: Some(
            isLocked
          ) :: lang :: HNil =>
        Some(
          Model(
            id,
            createdAt,
            User(
              id,
              fullName,
              name,
              email,
              tagline,
              joinDate,
              readPrompts,
              isLocked,
              lang
            )
          )
        )
      case _ => None
    }

  implicit val apiKeyRead: Read[ApiKey] = Read[String :: DbRef[User] :: String :: Permission :: HNil].map {
    case name :: ownerId :: token :: permissions :: HNil => ApiKey(name, ownerId, token, permissions)
  }

  implicit val apiKeyOptRead: Read[Option[ApiKey]] =
    Read[Option[String] :: Option[DbRef[User]] :: Option[String] :: Option[Permission] :: HNil].map {
      case Some(name) :: Some(ownerId) :: Some(token) :: Some(permissions) :: HNil =>
        Some(ApiKey(name, ownerId, token, permissions))
      case _ => None
    }
}
object DoobieOreProtocol extends DoobieOreProtocol