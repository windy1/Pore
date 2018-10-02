package controllers

import java.sql.Timestamp
import java.time.Instant

import javax.inject.Inject

import scala.concurrent.{ExecutionContext, Future}
import play.api.cache.AsyncCacheApi
import play.api.mvc.{Action, AnyContent, Result}
import controllers.sugar.Bakery
import controllers.sugar.Requests.AuthRequest
import db.impl.OrePostgresDriver.api._
import db.impl.schema._
import db.{ModelService, ObjectId, ObjectReference, ObjectTimestamp}
import form.OreForms
import models.admin.{Message, Review}
import models.project.{Project, Version, Visibility}
import models.user.{LoggedAction, Notification, User, UserActionLogger}
import ore.permission.ReviewProjects
import ore.permission.role.{Lifted, RoleType}
import ore.user.notification.NotificationType
import ore.{OreConfig, OreEnv}
import security.spauth.{SingleSignOnConsumer, SpongeAuthApi}
import views.{html => views}
import cats.data.{EitherT, NonEmptyList}
import cats.instances.future._
import cats.syntax.all._
import slick.lifted.{Rep, TableQuery}

/**
  * Controller for handling Review related actions.
  */
final class Reviews @Inject()(forms: OreForms)(
    implicit val ec: ExecutionContext,
    bakery: Bakery,
    auth: SpongeAuthApi,
    sso: SingleSignOnConsumer,
    env: OreEnv,
    cache: AsyncCacheApi,
    config: OreConfig,
    service: ModelService
) extends OreBaseController {

  private def queuePageSize              = this.config.ore.get[Int]("queue.page-size")
  private def totalPages(itemCount: Int) = math.ceil(itemCount.toDouble / queuePageSize).toInt

  /**
    * Shows the moderation queue for unreviewed versions.
    *
    * @return View of unreviewed versions.
    */
  def showVersionQueuePending(page: Option[Int]): Action[AnyContent] =
    Authenticated.andThen(PermissionAction[AuthRequest](ReviewProjects)).async { implicit request =>
      val currentPage = page.getOrElse(1)

      service.DB.db.run(queryVersionQueuePending(currentPage)).map { result =>
        Ok(views.users.admin.queue.pending(result._1, currentPage, totalPages(result._2)))
      }
    }

  /**
    * Shows the moderation queue for versions which are in review.
    *
    * @return View of versions which are in review.
    */
  def showVersionQueueWIP(page: Option[Int]): Action[AnyContent] =
    Authenticated.andThen(PermissionAction[AuthRequest](ReviewProjects)).async { implicit request =>
      val currentPage = page.getOrElse(1)

      service.DB.db.run(queryVersionQueueWIP(currentPage)).map { result =>
        Ok(views.users.admin.queue.wip(result._1, currentPage, totalPages(result._2)))
      }
    }

  /**
    * Shows the moderation queue for reviewed versions.
    *
    * @return View of reviewed versions.
    */
  def showVersionQueueDone(page: Option[Int]): Action[AnyContent] =
    Authenticated.andThen(PermissionAction[AuthRequest](ReviewProjects)).async { implicit request =>
      val currentPage = page.getOrElse(1)

      service.DB.db.run(queryVersionQueueDone(currentPage)).map { result =>
        Ok(views.users.admin.queue.done(result._1, currentPage, totalPages(result._2)))
      }
    }

  private def queryVersionQueuePending(page: Int) = {
    val versionTable = TableQuery[VersionTable]
    val channelTable = TableQuery[ChannelTable]
    val projectTable = TableQuery[ProjectTableMain]
    val userTable    = TableQuery[UserTable]
    val reviewsTable = TableQuery[ReviewTable]

    val base = (for {
      ((v, u), r) <- (versionTable
        .joinLeft(userTable)
        .on(_.authorId === _.id))
        .joinLeft(reviewsTable)
        .on(_._1.id === _.versionId)
      c <- channelTable
      if v.channelId === c.id && v.isReviewed =!= true && v.isNonReviewed =!= true && v.isReviewed === false && v.visibility =!= (Visibility.SoftDelete: Visibility)
      p  <- projectTable if v.projectId === p.id && p.visibility =!= (Visibility.SoftDelete: Visibility)
      ou <- userTable if p.userId === ou.id
    } yield {
      (v, p, c, u.map(_.name), ou, r)
    }).filter {
        case (_, _, _, _, _, r) =>
          r.isEmpty
      }
      .sortBy {
        case (v, _, _, _, _, _) =>
          v.createdAt.asc.nullsFirst
      }

    base.drop((page - 1) * queuePageSize).take(queuePageSize).result.zip(base.length.result)
  }

  private def queryVersionQueueWIP(page: Int) = {
    val versionTable = TableQuery[VersionTable]
    val channelTable = TableQuery[ChannelTable]
    val projectTable = TableQuery[ProjectTableMain]
    val userTable    = TableQuery[UserTable]
    val reviewsTable = TableQuery[ReviewTable]

    val base = (for {
      (v, u) <- versionTable.joinLeft(userTable).on(_.authorId === _.id)
      c      <- channelTable
      if v.channelId === c.id && v.isReviewed =!= true && v.isNonReviewed =!= true && v.isReviewed === false && v.visibility =!= (Visibility.SoftDelete: Visibility)
      p  <- projectTable if v.projectId === p.id && p.visibility =!= (Visibility.SoftDelete: Visibility)
      ou <- userTable if p.userId === ou.id
      r  <- reviewsTable if r.versionId === v.id //todo: only get latest review for each project
      ru <- userTable if r.userId === ru.id
    } yield {
      (v, p, c, u.map(_.name), ou, r, ru)
    }).sortBy {
      case (_, _, _, _, _, r, _) =>
        r.createdAt.desc.nullsLast
    }

    base.drop((page - 1) * queuePageSize).take(queuePageSize).result.zip(base.length.result)
  }

  private def queryVersionQueueDone(page: Int) = {
    val versionTable = TableQuery[VersionTable]
    val channelTable = TableQuery[ChannelTable]
    val projectTable = TableQuery[ProjectTableMain]
    val userTable    = TableQuery[UserTable]

    val base = (for {
      (v, u) <- versionTable.joinLeft(userTable).on(_.authorId === _.id)
      c      <- channelTable
      if v.channelId === c.id && v.isNonReviewed =!= true && c.isNonReviewed =!= true && v.isReviewed === true && v.visibility =!= (Visibility.SoftDelete: Visibility)
      p  <- projectTable if v.projectId === p.id && p.visibility =!= (Visibility.SoftDelete: Visibility)
      ou <- userTable if p.userId === ou.id
      ru <- userTable if v.reviewerId === ru.id
    } yield {
      (v, p, c, u.map(_.name), ou, ru)
    }).sortBy {
      case (v, _, _, _, _, _) =>
        v.approvedAt.desc.nullsLast
    }

    base.drop((page - 1) * queuePageSize).take(queuePageSize).result.zip(base.length.result)
  }

  def showReviews(author: String, slug: String, versionString: String): Action[AnyContent] =
    Authenticated.andThen(PermissionAction(ReviewProjects)).andThen(ProjectAction(author, slug)).asyncEitherT {
      implicit request =>
        for {
          version <- getVersion(request.project, versionString)
          reviews <- EitherT.right[Result](version.mostRecentReviews)
          rv <- EitherT.right[Result](
            Future.traverse(reviews)(r => users.get(r.userId).map(_.name).value.tupleLeft(r))
          )
        } yield {
          val unfinished = reviews.filter(_.endedAt.isEmpty).sorted(Review.ordering2).headOption
          Ok(views.users.admin.reviews(unfinished, rv, request.project, version))
        }
    }

  def createReview(author: String, slug: String, versionString: String): Action[AnyContent] = {
    Authenticated.andThen(PermissionAction(ReviewProjects)).async { implicit request =>
      getProjectVersion(author, slug, versionString).semiflatMap { version =>
        val review = new Review(
          ObjectId.Uninitialized,
          ObjectTimestamp(Timestamp.from(Instant.now())),
          version.id.value,
          request.user.id.value,
          None,
          ""
        )
        this.service.insert(review).as(Redirect(routes.Reviews.showReviews(author, slug, versionString)))
      }.merge
    }
  }

  def reopenReview(author: String, slug: String, versionString: String): Action[AnyContent] = {
    Authenticated.andThen(PermissionAction(ReviewProjects)).asyncEitherT { implicit request =>
      for {
        version <- getProjectVersion(author, slug, versionString)
        review  <- EitherT.fromOptionF(version.mostRecentReviews.map(_.headOption), notFound)
        _ <- EitherT.right[Result](
          service.update(
            version.copy(
              isReviewed = false,
              approvedAt = None,
              reviewerId = None
            )
          )
        )
        _ <- EitherT.right[Result](
          service
            .update(review.copy(endedAt = None))
            .flatMap(_.addMessage(Message("Reopened the review", System.currentTimeMillis(), "start")))
        )
      } yield Redirect(routes.Reviews.showReviews(author, slug, versionString))
    }
  }

  def stopReview(author: String, slug: String, versionString: String): Action[String] = {
    Authenticated
      .andThen(PermissionAction(ReviewProjects))
      .asyncEitherT(parse.form(forms.ReviewDescription)) { implicit request =>
        for {
          version <- getProjectVersion(author, slug, versionString)
          review  <- version.mostRecentUnfinishedReview.toRight(notFound)
          _ <- EitherT.right[Result](
            service
              .update(review.copy(endedAt = Some(Timestamp.from(Instant.now()))))
              .flatMap(_.addMessage(Message(request.body.trim, System.currentTimeMillis(), "stop")))
          )
        } yield Redirect(routes.Reviews.showReviews(author, slug, versionString))
      }
  }

  def approveReview(author: String, slug: String, versionString: String): Action[AnyContent] = {
    Authenticated.andThen(PermissionAction(ReviewProjects)).asyncEitherT { implicit request =>
      for {
        project <- getProject(author, slug)
        version <- getVersion(project, versionString)
        review  <- version.mostRecentUnfinishedReview.toRight(notFound)
        _ <- EitherT.right[Result](
          (
            service.update(review.copy(endedAt = Some(Timestamp.from(Instant.now())))),
            // send notification that review happened
            sendReviewNotification(project, version, request.user)
          ).tupled
        )
      } yield Redirect(routes.Reviews.showReviews(author, slug, versionString))
    }
  }

  private def queryNotificationUsers(
      projectId: Rep[ObjectReference],
      userId: Rep[ObjectReference],
      noRole: Rep[Option[RoleType]]
  ): Query[(Rep[ObjectReference], Rep[Option[RoleType]]), (ObjectReference, Option[RoleType]), Seq] = {
    // Query Orga Members
    val q1 = for {
      org     <- TableQuery[OrganizationTable] if org.id === projectId
      members <- TableQuery[OrganizationMembersTable] if org.id === members.organizationId
      roles   <- TableQuery[OrganizationRoleTable] if members.userId === roles.userId // TODO roletype lvl in database?
      users   <- TableQuery[UserTable] if members.userId === users.id
    } yield (users.id, roles.roleType.?)

    // Query version author
    val q2 = for {
      user <- TableQuery[UserTable] if user.id === userId
    } yield (user.id, noRole)

    q1 ++ q2 // Union
  }

  private lazy val notificationUsersQuery = Compiled(queryNotificationUsers _)

  private def sendReviewNotification(project: Project, version: Version, requestUser: User): Future[_] = {
    val futUsers =
      service.doAction(notificationUsersQuery((project.id.value, version.authorId, None)).result).map { list =>
        list
          .filter {
            case (_, Some(level)) => level.trust.level >= Lifted.level
            case (_, None)        => true
          }
          .map(_._1)
      }

    futUsers
      .map { users =>
        users.map { userId =>
          Notification(
            userId = userId,
            createdAt = ObjectTimestamp(Timestamp.from(Instant.now())),
            originId = requestUser.id.value,
            notificationType = NotificationType.VersionReviewed,
            messageArgs = NonEmptyList.of("notification.project.reviewed", project.slug, version.versionString)
          )
        }
      }
      .map(TableQuery[NotificationTable] ++= _)
      .flatMap(service.doAction) // Batch insert all notifications
  }

  def takeoverReview(author: String, slug: String, versionString: String): Action[String] = {
    Authenticated
      .andThen(PermissionAction(ReviewProjects))
      .asyncEitherT(parse.form(forms.ReviewDescription)) { implicit request =>
        for {
          version <- getProjectVersion(author, slug, versionString)
          _ <- {
            // Close old review
            val closeOldReview = version.mostRecentUnfinishedReview
              .semiflatMap { oldreview =>
                (
                  oldreview.addMessage(Message(request.body.trim, System.currentTimeMillis(), "takeover")),
                  service.update(oldreview.copy(endedAt = Some(Timestamp.from(Instant.now())))),
                ).tupled.void
              }
              .getOrElse(())

            // Then make new one
            val result = (
              closeOldReview,
              this.service.insert(
                Review(
                  ObjectId.Uninitialized,
                  ObjectTimestamp(Timestamp.from(Instant.now())),
                  version.id.value,
                  request.user.id.value,
                  None,
                  ""
                )
              )
            ).tupled
            EitherT.right[Result](result)
          }
        } yield Redirect(routes.Reviews.showReviews(author, slug, versionString))
      }
  }

  def editReview(author: String, slug: String, versionString: String, reviewId: ObjectReference): Action[String] = {
    Authenticated
      .andThen(PermissionAction(ReviewProjects))
      .asyncEitherT(parse.form(forms.ReviewDescription)) { implicit request =>
        for {
          version <- getProjectVersion(author, slug, versionString)
          review  <- version.reviewById(reviewId).toRight(notFound)
          _       <- EitherT.liftF(review.addMessage(Message(request.body.trim)))
        } yield Ok("Review" + review)
      }
  }

  def addMessage(author: String, slug: String, versionString: String): Action[String] = {
    Authenticated.andThen(PermissionAction(ReviewProjects)).asyncEitherT(parse.form(forms.ReviewDescription)) {
      implicit request =>
        for {
          version      <- getProjectVersion(author, slug, versionString)
          recentReview <- version.mostRecentUnfinishedReview.toRight(Ok("Review"))
          currentUser  <- users.current.toRight(Ok("Review"))
          _ <- {
            if (recentReview.userId == currentUser.userId) {
              EitherT.right[Result](recentReview.addMessage(Message(request.body.trim)))
            } else EitherT.rightT[Future, Result](0)
          }
        } yield Ok("Review")
    }
  }

  def shouldReviewToggle(author: String, slug: String, versionString: String): Action[AnyContent] = {
    Authenticated.andThen(PermissionAction[AuthRequest](ReviewProjects)).asyncEitherT { implicit request =>
      for {
        version <- getProjectVersion(author, slug, versionString)
        _ <- EitherT.liftF(
          UserActionLogger.log(
            request,
            LoggedAction.VersionNonReviewChanged,
            version.id.value,
            s"In review pending: ${version.isNonReviewed}",
            s"In review pending: ${!version.isNonReviewed}"
          )
        )
        _ <- EitherT.liftF(service.update(version.copy(isNonReviewed = !version.isNonReviewed)))
      } yield Redirect(routes.Reviews.showReviews(author, slug, versionString))
    }
  }
}
