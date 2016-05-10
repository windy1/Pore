package form

import javax.inject.Inject

import db.ModelService
import models.project.Channel
import models.project.Page._
import ore.project.util.ProjectFileManager
import play.api.data.Form
import play.api.data.Forms._
import util.OreConfig

/**
  * Collection of forms used in this application.
  */
//noinspection ConvertibleToMethodValue
class OreForms @Inject()(implicit val config: OreConfig,
                         val service: ModelService,
                         val fileManager: ProjectFileManager) {

  /**
    * Submits a new Channel for a Project.
    */
  lazy val ChannelEdit = Form(mapping(
    "channel-input" -> text.verifying(
      "Invalid channel name.", Channel.isValidName(_)
    ),

    "channel-color-input" -> text.verifying(
      "Invalid channel color.", c => Channel.Colors.exists(_.hex.equalsIgnoreCase(c))
    )
  )(ChannelData.apply)(ChannelData.unapply))

  /**
    * Submits a member to be removed from a Project.
    */
  lazy val MemberRemove = Form(single("username" -> text))

  /**
    * Submits changes to a [[models.project.Project]]'s
    * [[models.user.ProjectRole]]s.
    */
  lazy val MemberRoles = Form(mapping(
    "users" -> list(number),
    "roles" -> list(text)
  )(ProjectRoleSetBuilder.apply)(ProjectRoleSetBuilder.unapply))

  /**
    * Submits changes on a documentation page.
    */
  lazy val PageEdit = Form(single(
    "content" -> text(
      minLength = MinLength,
      maxLength = MaxLength
    )))

  /**
    * Submits a flag on a project for further review.
    */
  lazy val ProjectFlag = Form(single("flag-reason" -> number))

  /**
    * Submits settings changes for a Project.
    */
  lazy val ProjectSave = Form(mapping(
    "category" -> text,
    "issues" -> text,
    "source" -> text,
    "description" -> text,
    "users" -> list(number),
    "roles" -> list(text),
    "userUps" -> list(text),
    "roleUps" -> list(text)
  )(ProjectSettings.apply)(ProjectSettings.unapply))

  /**
    * Submits a name change for a project.
    */
  lazy val ProjectRename = Form(single("name" -> text))

  /**
    * Submits a post reply for a project discussion.
    */
  lazy val ProjectReply = Form(single("content" -> text(minLength = MinLength, maxLength = MaxLength)))

  /**
    * Submits a tagline change for a User.
    */
  lazy val UserTagline = Form(single("tagline" -> text))

  /**
    * Submits a new Version.
    */
  lazy val VersionCreate = Form(mapping(
    "channel-input" -> text.verifying(
      "Invalid channel name.", Channel.isValidName(_)
    ),

    "channel-color-input" -> text.verifying(
      "Invalid channel color.", c => Channel.Colors.exists(_.hex.equalsIgnoreCase(c))
    ),

    "recommended" -> boolean
  )(VersionData.apply)(VersionData.unapply))


  /**
    * Submits a change to a Version's description.
    */
  lazy val VersionDescription = Form(single("content" -> text))

}