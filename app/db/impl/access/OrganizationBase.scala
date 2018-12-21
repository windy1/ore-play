package db.impl.access

import db.{DbRef, ModelBase, ModelService, ObjId}
import models.user.role.OrganizationUserRole
import models.user.{Notification, Organization, User}
import ore.OreConfig
import ore.permission.role.Role
import ore.user.notification.NotificationType
import security.spauth.SpongeAuthApi
import util.StringUtils

import cats.data.{EitherT, NonEmptyList, OptionT}
import cats.effect.{ContextShift, IO}
import cats.syntax.all._

class OrganizationBase(implicit val service: ModelService, config: OreConfig) extends ModelBase[Organization] {

  val Logger = play.api.Logger("Organizations")

  /**
    * Creates a new [[Organization]]. This method creates a new user on the
    * forums to represent the Organization.
    *
    * @param name     Organization name
    * @param ownerId  User ID of the organization owner
    * @return         New organization if successful, None otherwise
    */
  def create(
      name: String,
      ownerId: DbRef[User],
      members: Set[OrganizationUserRole.Partial]
  )(implicit auth: SpongeAuthApi, cs: ContextShift[IO]): EitherT[IO, String, Organization] = {
    import cats.instances.vector._
    Logger.debug("Creating Organization...")
    Logger.debug("Name     : " + name)
    Logger.debug("Owner ID : " + ownerId)
    Logger.debug("Members  : " + members.size)

    // Create the organization as a User on SpongeAuth. This will reserve the
    // name so that no new users or organizations can create an account with
    // that name. We will give the organization a dummy email for continuity.
    // By default we use "<org>@ore.spongepowered.org".
    Logger.debug("Creating on SpongeAuth...")
    val dummyEmail   = name + '@' + this.config.ore.orgs.dummyEmailDomain
    val spongeResult = auth.createDummyUser(name, dummyEmail)

    // Check for error
    spongeResult
      .leftMap { err =>
        Logger.debug("<FAILURE> " + err)
        err
      }
      .semiflatMap { spongeUser =>
        Logger.debug("<SUCCESS> " + spongeUser)
        // Next we will create the Organization on Ore itself. This contains a
        // reference to the Sponge user ID, the organization's username and a
        // reference to the User owner of the organization.
        Logger.info("Creating on Ore...")
        this.add(Organization.partial(id = ObjId(spongeUser.id), username = name, ownerId = ownerId))
      }
      .semiflatMap { org =>
        // Every organization model has a regular User companion. Organizations
        // are just normal users with additional information. Adding the
        // Organization global role signifies that this User is an Organization
        // and should be treated as such.
        for {
          userOrg <- org.toUser.getOrElse(throw new IllegalStateException("User not created"))
          _       <- userOrg.globalRoles.addAssoc(userOrg, Role.Organization.toDbRole)
          _ <- // Add the owner
          org.memberships.addRole(
            org,
            ownerId,
            OrganizationUserRole
              .Partial(
                userId = ownerId,
                organizationId = org.id.value,
                role = Role.OrganizationOwner,
                isAccepted = true
              )
              .asFunc
          )
          _ <- {
            // Invite the User members that the owner selected during creation.
            Logger.debug("Inviting members...")

            members.toVector.parTraverse { role =>
              // TODO remove role.user db access we really only need the userid we already have for notifications
              org.memberships.addRole(org, role.userId, role.copy(organizationId = org.id.value).asFunc).flatMap { _ =>
                service.insert(
                  Notification.partial(
                    userId = role.userId,
                    originId = org.id.value,
                    notificationType = NotificationType.OrganizationInvite,
                    messageArgs = NonEmptyList.of("notification.organization.invite", role.role.title, org.username)
                  )
                )
              }
            }
          }
        } yield {
          Logger.debug("<SUCCESS> " + org)
          org
        }
      }
  }

  /**
    * Returns an [[Organization]] with the specified name if it exists.
    *
    * @param name Organization name
    * @return     Organization with name if exists, None otherwise
    */
  def withName(name: String): OptionT[IO, Organization] =
    this.find(StringUtils.equalsIgnoreCase(_.name, name))

}
object OrganizationBase {
  def apply()(implicit organizationBase: OrganizationBase): OrganizationBase = organizationBase

  implicit def fromService(implicit service: ModelService): OrganizationBase = service.organizationBase
}
