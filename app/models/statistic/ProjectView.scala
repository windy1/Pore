package models.statistic

import java.sql.Timestamp

import com.github.tminglei.slickpg.InetString
import controllers.Requests.ProjectRequest
import db.{Model, ModelService}
import db.impl.ProjectViewsTable
import db.meta.{Bind, ModelProcessor}
import db.action.StatActions
import models.project.Project
import models.user.User
import ore.{ProjectOwner, Statistics}

import scala.annotation.meta.field

/**
  * Represents a unique view on a Project.
  *
  * @param id         Unique ID of entry
  * @param createdAt  Timestamp instant of creation
  * @param modelId    ID of model the stat is on
  * @param address    Client address
  * @param cookie     Browser cookie
  * @param userId     User ID
  */
case class ProjectView(override val id: Option[Int] = None,
                       override val createdAt: Option[Timestamp] = None,
                       override val modelId: Int,
                       override val address: InetString,
                       override val cookie: String,
                       @(Bind @field) private var userId: Option[Int] = None)
                       extends StatEntry[Project](
                         id, createdAt, modelId, address, cookie, userId
                       ) with ProjectOwner {

  override def subject(implicit service: ModelService): Project = Project.withId(this.modelId).get

  override def copyWith(id: Option[Int], theTime: Option[Timestamp]): ProjectView
  = this.copy(id = id, createdAt = theTime)

  override def projectId: Int = this.modelId

}

object ProjectView {

  /**
    * Creates a new ProjectView to be (or not be) recorded from an incoming
    * request.
    *
    * @param request  Request to bind
    * @return         New ProjectView
    */
  def bindFromRequest()(implicit request: ProjectRequest[_]): ProjectView = {
    val userId = User.current(request.session, request.service).flatMap(_.id)
    val cookie = Statistics.getStatCookie
    ProjectView(
      modelId = request.project.id.get,
      address = InetString(request.remoteAddress),
      cookie = cookie,
      userId = userId
    )
  }

}