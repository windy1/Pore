package controllers.project

import java.nio.file.{Files, Path}
import javax.inject.Inject

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

import play.api.cache.AsyncCacheApi
import play.api.i18n.MessagesApi
import play.api.libs.Files.TemporaryFile
import play.api.mvc.{Action, AnyContent, MultipartFormData, Result}

import controllers.OreBaseController
import controllers.sugar.Bakery
import controllers.sugar.Requests.AuthRequest
import db.impl.OrePostgresDriver.api._
import db.{ModelService, ObjectReference}
import discourse.OreDiscourseApi
import form.OreForms
import form.project.{DiscussionReplyForm, FlagForm, ProjectRoleSetBuilder}
import models.project.{Note, Visibility}
import models.user._
import models.viewhelper.ScopedOrganizationData
import ore.permission._
import ore.permission.scope.GlobalScope
import ore.project.factory.ProjectFactory
import ore.project.io.{InvalidPluginFileException, PluginUpload, ProjectFiles}
import ore.rest.ProjectApiKeyType
import ore.user.MembershipDossier
import ore.user.MembershipDossier._
import ore.{OreConfig, OreEnv, StatTracker}
import security.spauth.{SingleSignOnConsumer, SpongeAuthApi}
import _root_.util.StringUtils._
import views.html.{projects => views}

import cats.data.{EitherT, OptionT}
import cats.instances.future._
import cats.syntax.all._

/**
  * Controller for handling Project related actions.
  */
class Projects @Inject()(stats: StatTracker, forms: OreForms, factory: ProjectFactory)(
    implicit val ec: ExecutionContext,
    cache: AsyncCacheApi,
    bakery: Bakery,
    sso: SingleSignOnConsumer,
    auth: SpongeAuthApi,
    forums: OreDiscourseApi,
    messagesApi: MessagesApi,
    env: OreEnv,
    config: OreConfig,
    service: ModelService
) extends OreBaseController {

  implicit val fileManager: ProjectFiles = factory.fileManager

  private val self = controllers.project.routes.Projects

  private def SettingsEditAction(author: String, slug: String) =
    AuthedProjectAction(author, slug, requireUnlock = true).andThen(ProjectPermissionAction(EditSettings))

  /**
    * Displays the "create project" page.
    *
    * @return Create project view
    */
  def showCreator(): Action[AnyContent] = UserLock().async { implicit request =>
    for {
      orgas      <- request.user.organizations.all
      createOrga <- Future.traverse(orgas)(request.user.can(CreateProject).in(_))
    } yield {
      Ok(views.creation.step1((true, ""), Seq.empty))
    }
  }

  /**
    * Uploads a Project's first plugin file for further processing.
    *
    * @return Result
    */
  def upload(): Action[AnyContent] = UserLock() { implicit request =>
    val user = request.user
    this.factory.getUploadError(user) match {
      case Some(error) => Redirect(self.showCreator()).withError(error)
      case None =>
        PluginUpload.bindFromRequest() match {
          case None =>
            Redirect(self.showCreator()).withError("error.noFile")
          case Some(uploadData) =>
            try {
              val plugin = this.factory.processPluginUpload(uploadData, user)
              plugin match {
                case Right(pluginFile) =>
                  val project = this.factory.startProject(pluginFile)
                  project.cache()
                  val model = project.underlying
                  Redirect(self.showCreatorWithMeta(model.ownerName, model.slug))
                case Left(errorMessage) =>
                  Redirect(self.showCreator()).withError(errorMessage)
              }
            } catch {
              case e: InvalidPluginFileException =>
                Redirect(self.showCreator()).withErrors(Option(e.getMessage).toList)
            }
        }
    }
  }

  /**
    * Displays the "create project" page with uploaded plugin meta data.
    *
    * @param author Author of plugin
    * @param slug   Project slug
    * @return Create project view
    */
  def showCreatorWithMeta(author: String, slug: String): Action[AnyContent] = UserLock().async { implicit request =>
    this.factory.getPendingProject(author, slug) match {
      case None => Future.successful(Redirect(self.showCreator()).withError("error.project.timeout"))
      case Some(pending) =>
        for {
          (orgas, owner) <- (request.user.organizations.all, pending.underlying.owner.user).tupled
          createOrga     <- Future.traverse(orgas)(owner.can(CreateProject).in(_))
        } yield {
          val createdOrgas = orgas.zip(createOrga).collect {
            case (orga, true) => orga
          }
          Ok(views.create(createdOrgas.toSeq, Some(pending)))
        }
    }
  }

  /**
    * Shows the members invitation page during Project creation.
    *
    * @param author   Project owner
    * @param slug     Project slug
    * @return         View of members config
    */
  def showInvitationForm(author: String, slug: String): Action[AnyContent] = UserLock().async { implicit request =>
    orgasUserCanUploadTo(request.user).flatMap { organisationUserCanUploadTo =>
      this.factory.getPendingProject(author, slug) match {
        case None =>
          Future.successful(Redirect(self.showCreator()).withError("error.project.timeout"))
        case Some(pendingProject) =>
          this.forms
            .ProjectSave(organisationUserCanUploadTo.toSeq)
            .bindFromRequest()
            .fold(
              FormErrorLocalized(self.showCreator()).andThen(Future.successful),
              formData => {
                pendingProject.settings.save(pendingProject.underlying, formData).flatMap {
                  case (newProject, newSettings) =>
                    val newPending = pendingProject.copy(
                      underlying = newProject,
                      settings = newSettings
                    )
                    newPending.cache()

                    val version   = newPending.pendingVersion
                    val namespace = newProject.namespace
                    this.cache.set(namespace, newPending)
                    this.cache.set(namespace + '/' + version.underlying.versionString, version)
                    implicit val currentUser: User = request.user

                    val authors = newPending.file.data.get.authors.toList
                    (
                      Future.traverse(authors.filter(_ != currentUser.name))(users.withName(_).value),
                      this.forums.countUsers(authors),
                      newPending.underlying.owner.user
                    ).mapN { (users, registered, owner) =>
                      Ok(views.invite(owner, newPending, users.flatten, registered))
                    }
                }
              }
            )
      }
    }
  }

  private def orgasUserCanUploadTo(user: User): Future[Set[ObjectReference]] = {
    for {
      all       <- user.organizations.all
      canCreate <- Future.traverse(all)(org => user.can(CreateProject).in(org).tupleLeft(org.id.value))
    } yield {
      // Filter by can Create Project
      val others = canCreate.collect {
        case (id, true) => id
      }

      others + user.id.value // Add self
    }
  }

  /**
    * Continues on to the second step of Project creation where the user
    * publishes their Project.
    *
    * @param author Author of project
    * @param slug   Project slug
    * @return Redirection to project page if successful
    */
  def showFirstVersionCreator(author: String, slug: String): Action[ProjectRoleSetBuilder] =
    UserLock()(parse.form(forms.ProjectMemberRoles)) { implicit request =>
      this.factory
        .getPendingProject(author, slug)
        .toRight(Redirect(self.showCreator()).withError("error.project.timeout"))
        .map { pendingProject =>
          val newPending = pendingProject.copy(roles = request.body.build())
          newPending.cache()
          val pendingVersion = newPending.pendingVersion
          Redirect(routes.Versions.showCreatorWithMeta(author, slug, pendingVersion.underlying.versionString))
        }
        .merge
    }

  /**
    * Displays the Project with the specified author and name.
    *
    * @param author Owner of project
    * @param slug   Project slug
    * @return View of project
    */
  def show(author: String, slug: String): Action[AnyContent] = ProjectAction(author, slug).async { implicit request =>
    projects.queryProjectPages(request.project).flatMap { pages =>
      val pageCount = pages.size + pages.map(_._2.size).sum
      this.stats.projectViewed(
        Ok(views.pages.view(request.data, request.scoped, pages, request.project.homePage, None, pageCount))
      )
    }
  }

  /**
    * Shortcut for navigating to a project.
    *
    * @param pluginId Project pluginId
    * @return Redirect to project page.
    */
  def showProjectById(pluginId: String): Action[AnyContent] = OreAction.async { implicit request =>
    projects.withPluginId(pluginId).fold(notFound) { project =>
      Redirect(self.show(project.ownerName, project.slug))
    }
  }

  /**
    * Displays the "discussion" tab within a Project view.
    *
    * @param author Owner of project
    * @param slug   Project slug
    * @return View of project
    */
  def showDiscussion(author: String, slug: String): Action[AnyContent] = ProjectAction(author, slug).async {
    implicit request =>
      this.stats.projectViewed(Ok(views.discuss(request.data, request.scoped)))
  }

  /**
    * Posts a new discussion reply to the forums.
    *
    * @param author Project owner
    * @param slug   Project slug
    * @return       View of discussion with new post
    */
  def postDiscussionReply(author: String, slug: String): Action[DiscussionReplyForm] =
    AuthedProjectAction(author, slug).async(
      parse.form(forms.ProjectReply, onErrors = FormError(self.showDiscussion(author, slug)))
    ) { implicit request =>
      val formData = request.body
      if (request.project.topicId.isEmpty)
        Future.successful(BadRequest)
      else {
        // Do forum post and display errors to user if any
        for {
          poster <- {
            OptionT
              .fromOption[Future](formData.poster)
              .flatMap(posterName => users.requestPermission(request.user, posterName, PostAsOrganization))
              .getOrElse(request.user)
          }
          errors <- this.forums.postDiscussionReply(request.project, poster, formData.content)
        } yield Redirect(self.showDiscussion(author, slug)).withErrors(errors)
      }
    }

  /**
    * Redirect's to the project's issue tracker if any.
    *
    * @param author Project owner
    * @param slug   Project slug
    * @return Issue tracker
    */
  def showIssues(author: String, slug: String): Action[AnyContent] = ProjectAction(author, slug) { implicit request =>
    request.data.settings.issues match {
      case None       => notFound
      case Some(link) => Redirect(link)
    }
  }

  /**
    * Redirect's to the project's source code if any.
    *
    * @param author Project owner
    * @param slug   Project slug
    * @return Source code
    */
  def showSource(author: String, slug: String): Action[AnyContent] = ProjectAction(author, slug) { implicit request =>
    request.data.settings.source match {
      case None       => notFound
      case Some(link) => Redirect(link)
    }
  }

  /**
    * Shows either a customly uploaded icon for a [[models.project.Project]]
    * or the owner's avatar if there is none.
    *
    * @param author Project owner
    * @param slug Project slug
    * @return Project icon
    */
  def showIcon(author: String, slug: String): Action[AnyContent] = Action.async { implicit request =>
    // TODO maybe instead of redirect cache this on ore?
    projects
      .withSlug(author, slug)
      .semiflatMap { project =>
        projects.fileManager.getIconPath(project) match {
          case None           => project.owner.user.map(user => Redirect(user.avatarUrl))
          case Some(iconPath) => Future.successful(showImage(iconPath))
        }
      }
      .getOrElse(NotFound)
  }

  private def showImage(path: Path) = Ok(Files.readAllBytes(path)).as("image/jpeg")

  /**
    * Submits a flag on the specified project for further review.
    *
    * @param author Project owner
    * @param slug   Project slug
    * @return       View of project
    */
  def flag(author: String, slug: String): Action[FlagForm] =
    AuthedProjectAction(author, slug).async(
      parse.form(forms.ProjectFlag, onErrors = FormErrorLocalized(ShowProject(author, slug)))
    ) { implicit request =>
      val user     = request.user
      val project  = request.project
      val formData = request.body

      user.hasUnresolvedFlagFor(project).flatMap {
        // One flag per project, per user at a time
        case true => Future.successful(BadRequest)
        case false =>
          project
            .flagFor(user, formData.reason, formData.comment)
            .productR(
              UserActionLogger.log(
                request.request,
                LoggedAction.ProjectFlagged,
                project.id.value,
                s"Flagged by ${user.name}",
                s"Not flagged by ${user.name}"
              )
            )
            .as(Redirect(self.show(author, slug)).flashing("reported" -> "true"))
      }
    }

  /**
    * Sets whether a [[models.user.User]] is watching a project.
    *
    * @param author   Project owner
    * @param slug     Project slug
    * @param watching True if watching
    * @return         Ok
    */
  def setWatching(author: String, slug: String, watching: Boolean): Action[AnyContent] =
    AuthedProjectAction(author, slug).async { implicit request =>
      request.user.setWatching(request.project, watching).as(Ok)
    }

  /**
    * Sets the "starred" status of a Project for the current user.
    *
    * @param author  Project owner
    * @param slug    Project slug
    * @param starred True if should set to starred
    * @return Result code
    */
  def setStarred(author: String, slug: String, starred: Boolean): Action[AnyContent] =
    AuthedProjectAction(author, slug).async { implicit request =>
      if (request.project.ownerId != request.user.userId)
        request.data.project.setStarredBy(request.user, starred).as(Ok)
      else
        Future.successful(BadRequest)
    }

  /**
    * Sets the status of a pending Project invite for the current user.
    *
    * @param id     Invite ID
    * @param status Invite status
    * @return       NotFound if invite doesn't exist, Ok otherwise
    */
  def setInviteStatus(id: ObjectReference, status: String): Action[AnyContent] = Authenticated.async {
    implicit request =>
      val user = request.user
      user.projectRoles
        .get(id)
        .semiflatMap { role =>
          status match {
            case STATUS_DECLINE  => role.project.flatMap(MembershipDossier.project.removeRole(_, role)).as(Ok)
            case STATUS_ACCEPT   => service.update(role.copy(isAccepted = true)).as(Ok)
            case STATUS_UNACCEPT => service.update(role.copy(isAccepted = false)).as(Ok)
            case _               => Future.successful(BadRequest)
          }
        }
        .getOrElse(NotFound)
  }

  /**
    * Sets the status of a pending Project invite on behalf of the Organization
    *
    * @param id     Invite ID
    * @param status Invite status
    * @param behalf Behalf User
    * @return       NotFound if invite doesn't exist, Ok otherwise
    */
  def setInviteStatusOnBehalf(id: ObjectReference, status: String, behalf: String): Action[AnyContent] =
    Authenticated.async { implicit request =>
      val user = request.user
      val res = for {
        orga       <- organizations.withName(behalf)
        orgaUser   <- users.withName(behalf)
        role       <- orgaUser.projectRoles.get(id)
        scopedData <- OptionT.liftF(ScopedOrganizationData.of(Some(user), orga))
        if scopedData.permissions.getOrElse(EditSettings, false)
        project <- OptionT.liftF(role.project)
        res <- OptionT.liftF[Future, Status] {
          status match {
            case STATUS_DECLINE  => project.memberships.removeRole(project, role).as(Ok)
            case STATUS_ACCEPT   => service.update(role.copy(isAccepted = true)).as(Ok)
            case STATUS_UNACCEPT => service.update(role.copy(isAccepted = false)).as(Ok)
            case _               => Future.successful(BadRequest)
          }
        }
      } yield res

      res.getOrElse(NotFound)
    }

  /**
    * Shows the project manager or "settings" pane.
    *
    * @param author Project owner
    * @param slug   Project slug
    * @return Project manager
    */
  def showSettings(author: String, slug: String): Action[AnyContent] = SettingsEditAction(author, slug).async {
    implicit request =>
      request.project.apiKeys
        .find(_.keyType === (ProjectApiKeyType.Deployment: ProjectApiKeyType))
        .value
        .map(deployKey => Ok(views.settings(request.data, request.scoped, deployKey)))
  }

  /**
    * Uploads a new icon to be saved for the specified [[models.project.Project]].
    *
    * @param author Project owner
    * @param slug   Project slug
    * @return       Ok or redirection if no file
    */
  def uploadIcon(author: String, slug: String): Action[MultipartFormData[TemporaryFile]] =
    SettingsEditAction(author, slug)(parse.multipartFormData) { implicit request =>
      request.body.file("icon") match {
        case None => Redirect(self.showSettings(author, slug)).withError("error.noFile")
        case Some(tmpFile) =>
          val data       = request.data
          val pendingDir = projects.fileManager.getPendingIconDir(data.project.ownerName, data.project.name)
          if (Files.notExists(pendingDir))
            Files.createDirectories(pendingDir)
          Files.list(pendingDir).iterator().asScala.foreach(Files.delete)
          tmpFile.ref.moveTo(pendingDir.resolve(tmpFile.filename).toFile, replace = true)
          UserActionLogger
            .log(request.request, LoggedAction.ProjectIconChanged, data.project.id.value, "", "") //todo data
          Ok
      }
    }

  /**
    * Resets the specified Project's icon to the default user avatar.
    *
    * @param author Project owner
    * @param slug   Project slug
    * @return       Ok
    */
  def resetIcon(author: String, slug: String): Action[AnyContent] = SettingsEditAction(author, slug) {
    implicit request =>
      val project     = request.project
      val fileManager = projects.fileManager
      fileManager.getIconPath(project).foreach(Files.delete)
      fileManager.getPendingIconPath(project).foreach(Files.delete)
      UserActionLogger.log(request.request, LoggedAction.ProjectIconChanged, project.id.value, "", "") //todo data
      Files.delete(fileManager.getPendingIconDir(project.ownerName, project.name))
      Ok
  }

  /**
    * Displays the specified [[models.project.Project]]'s current pending
    * icon, if any.
    *
    * @param author Project owner
    * @param slug   Project slug
    * @return       Pending icon
    */
  def showPendingIcon(author: String, slug: String): Action[AnyContent] =
    ProjectAction(author, slug) { implicit request =>
      projects.fileManager.getPendingIconPath(request.project) match {
        case None       => notFound
        case Some(path) => showImage(path)
      }
    }

  /**
    * Removes a [[ore.project.ProjectMember]] from the specified project.
    *
    * @param author Project owner
    * @param slug   Project slug
    */
  def removeMember(author: String, slug: String): Action[String] =
    SettingsEditAction(author, slug).async(parse.form(forms.ProjectMemberRemove)) { implicit request =>
      users
        .withName(request.body)
        .semiflatMap { user =>
          val project = request.data.project
          project.memberships
            .removeMember(project, user)
            .productR(
              UserActionLogger.log(
                request.request,
                LoggedAction.ProjectMemberRemoved,
                project.id.value,
                s"'${user.name}' is not a member of ${project.ownerName}/${project.name}",
                s"'${user.name}' is a member of ${project.ownerName}/${project.name}"
              )
            )
            .as(Redirect(self.showSettings(author, slug)))
        }
        .getOrElse(BadRequest)
    }

  /**
    * Saves the specified Project from the settings manager.
    *
    * @param author Project owner
    * @param slug   Project slug
    * @return View of project
    */
  def save(author: String, slug: String): Action[AnyContent] = SettingsEditAction(author, slug).async {
    implicit request =>
      orgasUserCanUploadTo(request.user).flatMap { organisationUserCanUploadTo =>
        val data = request.data
        this.forms
          .ProjectSave(organisationUserCanUploadTo.toSeq)
          .bindFromRequest()
          .fold(
            FormErrorLocalized(self.showSettings(author, slug)).andThen(Future.successful),
            formData => {
              data.settings
                .save(data.project, formData)
                .productR {
                  UserActionLogger.log(
                    request.request,
                    LoggedAction.ProjectSettingsChanged,
                    request.data.project.id.value,
                    "",
                    ""
                  ) //todo add old new data
                }
                .as(Redirect(self.show(author, slug)))
            }
          )
      }
  }

  /**
    * Renames the specified project.
    *
    * @param author Project owner
    * @param slug   Project slug
    * @return Project homepage
    */
  def rename(author: String, slug: String): Action[String] =
    SettingsEditAction(author, slug).asyncEitherT(parse.form(forms.ProjectRename)) { implicit request =>
      val project = request.data.project
      val newName = compact(request.body)
      val oldName = request.project.name

      for {
        available <- EitherT.right[Result](projects.isNamespaceAvailable(author, slugify(newName)))
        _ <- EitherT
          .cond[Future](available, (), Redirect(self.showSettings(author, slug)).withError("error.nameUnavailable"))
        _ <- EitherT.right[Result](projects.rename(project, newName))
        _ <- EitherT.liftF {
          UserActionLogger.log(
            request.request,
            LoggedAction.ProjectRenamed,
            request.project.id.value,
            s"$author/$newName",
            s"$author/$oldName"
          )
        }
      } yield Redirect(self.show(author, project.slug))
    }

  /**
    * Sets the visible state of the specified Project.
    *
    * @param author     Project owner
    * @param slug       Project slug
    * @param visibility Project visibility
    * @return         Ok
    */
  def setVisible(author: String, slug: String, visibility: Int): Action[AnyContent] = {
    AuthedProjectAction(author, slug, requireUnlock = true)
      .andThen(ProjectPermissionAction(HideProjects))
      .async { implicit request =>
        val newVisibility = Visibility.withValue(visibility)
        request.user.can(newVisibility.permission).in(GlobalScope).flatMap { perm =>
          if (perm) {
            if (!Visibility.isPublic(newVisibility) && Visibility.isPublic(request.project.visibility)) {
              this.forums.changeTopicVisibility(request.project, isVisible = false)
            } else if (Visibility.isPublic(newVisibility) && !Visibility.isPublic(request.project.visibility)) {
              this.forums.changeTopicVisibility(request.project, isVisible = true)
            }

            val change = if (newVisibility.showModal) {
              val comment = this.forms.NeedsChanges.bindFromRequest.get.trim
              request.project.setVisibility(newVisibility, comment, request.user.id.value)
            } else {
              request.project.setVisibility(newVisibility, "", request.user.id.value)
            }

            change
              .productR(
                UserActionLogger.log(
                  request.request,
                  LoggedAction.ProjectVisibilityChange,
                  request.project.id.value,
                  newVisibility.nameKey,
                  Visibility.NeedsChanges.nameKey
                )
              )
              .as(Ok)
          } else {
            Future.successful(Unauthorized)
          }
        }
      }
  }

  /**
    * Set a project that is in new to public
    * @param author   Project owner
    * @param slug     Project slug
    * @return         Redirect home
    */
  def publish(author: String, slug: String): Action[AnyContent] = SettingsEditAction(author, slug).async {
    implicit request =>
      val effects =
        if (request.data.visibility == Visibility.New)
          request.project
            .setVisibility(Visibility.Public, "", request.user.id.value)
            .productR(
              UserActionLogger.log(
                request.request,
                LoggedAction.ProjectVisibilityChange,
                request.project.id.value,
                Visibility.Public.nameKey,
                Visibility.New.nameKey
              )
            )
            .void
        else Future.unit

      effects.as(Redirect(self.show(request.project.ownerName, request.project.slug)))
  }

  /**
    * Set a project that needed changes to the approval state
    * @param author   Project owner
    * @param slug     Project slug
    * @return         Redirect home
    */
  def sendForApproval(author: String, slug: String): Action[AnyContent] = SettingsEditAction(author, slug).async {
    implicit request =>
      val effects = if (request.data.visibility == Visibility.NeedsChanges) {
        request.project
          .setVisibility(Visibility.NeedsApproval, "", request.user.id.value)
          .productR(
            UserActionLogger.log(
              request.request,
              LoggedAction.ProjectVisibilityChange,
              request.project.id.value,
              Visibility.NeedsApproval.nameKey,
              Visibility.NeedsChanges.nameKey
            )
          )
          .void
      } else Future.unit
      effects.as(Redirect(self.show(request.project.ownerName, request.project.slug)))
  }

  def showLog(author: String, slug: String): Action[AnyContent] = {
    Authenticated.andThen(PermissionAction(ViewLogs)).andThen(ProjectAction(author, slug)).async { implicit request =>
      for {
        logger <- request.project.logger
        logs   <- logger.entries.all
      } yield Ok(views.log(request.project, logs.toSeq))
    }
  }

  /**
    * Irreversibly deletes the specified project.
    *
    * @param author Project owner
    * @param slug   Project slug
    * @return Home page
    */
  def delete(author: String, slug: String): Action[AnyContent] = {
    Authenticated.andThen(PermissionAction(HardRemoveProject)).async { implicit request =>
      getProject(author, slug).semiflatMap { project =>
        val deletePost = if (project.topicId.isDefined) this.forums.deleteProjectTopic(project) else Future.unit

        val effects = deletePost *>
          projects.delete(project) *>
          UserActionLogger
            .log(request, LoggedAction.ProjectVisibilityChange, project.id.value, "deleted", project.visibility.nameKey)
        effects.as(Redirect(ShowHome).withSuccess(request.messages.apply("project.deleted", project.name)))
      }.merge
    }
  }

  /**
    * Soft deletes the specified project.
    *
    * @param author Project owner
    * @param slug   Project slug
    * @return Home page
    */
  def softDelete(author: String, slug: String): Action[String] =
    SettingsEditAction(author, slug).async(parse.form(forms.NeedsChanges)) { implicit request =>
      val oldProject = request.project
      val comment    = request.body.trim
      oldProject.setVisibility(Visibility.SoftDelete, comment, request.user.id.value).flatMap {
        case (newProject, _) =>
          this.forums
            .changeTopicVisibility(oldProject, isVisible = false)
            .productR(
              UserActionLogger.log(
                request.request,
                LoggedAction.ProjectVisibilityChange,
                oldProject.id.value,
                newProject.visibility.nameKey,
                oldProject.visibility.nameKey
              )
            )
            .as(Redirect(ShowHome).withSuccess(request.messages.apply("project.deleted", oldProject.name)))
      }
    }

  /**
    * Show the flags that have been made on this project
    *
    * @param author Project owner
    * @param slug   Project slug
    */
  def showFlags(author: String, slug: String): Action[AnyContent] =
    Authenticated.andThen(PermissionAction(ReviewFlags)).andThen(ProjectAction(author, slug)) { implicit request =>
      Ok(views.admin.flags(request.data))
    }

  /**
    * Show the notes that have been made on this project
    *
    * @param author Project owner
    * @param slug   Project slug
    */
  def showNotes(author: String, slug: String): Action[AnyContent] = {
    Authenticated.andThen(PermissionAction[AuthRequest](ReviewFlags)).async { implicit request =>
      getProject(author, slug).semiflatMap { project =>
        Future.traverse(project.decodeNotes)(note => users.get(note.user).value.tupleLeft(note)).map { notes =>
          Ok(views.admin.notes(project, notes))
        }
      }.merge
    }
  }

  def addMessage(author: String, slug: String): Action[String] = {
    Authenticated
      .andThen(PermissionAction[AuthRequest](ReviewProjects))
      .asyncEitherT(parse.form(forms.NoteDescription)) { implicit request =>
        getProject(author, slug)
          .semiflatMap(_.addNote(Note(request.body.trim, request.user.userId)))
          .map(_ => Ok("Review"))
      }
  }
}
