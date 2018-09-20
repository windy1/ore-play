package security.spauth

import java.util.concurrent.TimeoutException
import javax.inject.Inject

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

import play.api.Configuration
import play.api.i18n.Lang
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._
import play.api.libs.ws.{WSClient, WSResponse}

import ore.OreConfig
import _root_.util.WSUtils.parseJson

import cats.data.{EitherT, OptionT}
import cats.instances.future._
import com.google.common.base.Preconditions._

/**
  * Interfaces with the SpongeAuth Web API
  */
trait SpongeAuthApi {

  /** The base URL of the instance */
  def url: String

  /** Secret API key */
  def apiKey: String
  def ws: WSClient
  val timeout: Duration = 10.seconds

  val Logger = play.api.Logger("SpongeAuth")

  /**
    * Creates a "dummy" user that cannot log in and has no password.
    *
    * @param username Username
    * @param email    Email
    * @return         Newly created user
    */
  def createDummyUser(username: String, email: String)(
      implicit ec: ExecutionContext
  ): EitherT[Future, String, SpongeUser] = doCreateUser(username, email, None, dummy = true)

  private def doCreateUser(
      username: String,
      email: String,
      password: Option[String],
      dummy: Boolean = false
  )(implicit ec: ExecutionContext): EitherT[Future, String, SpongeUser] = {
    checkNotNull(username, "null username", "")
    checkNotNull(email, "null email", "")
    val params = Map(
      "api-key"  -> Seq(this.apiKey),
      "username" -> Seq(username),
      "email"    -> Seq(email),
      "verified" -> Seq(false.toString),
      "dummy"    -> Seq(dummy.toString)
    )

    val withPassword = password.fold(params)(pass => params + ("password" -> Seq(pass)))
    readUser(this.ws.url(route("/api/users")).withRequestTimeout(timeout).post(withPassword))
  }

  /**
    * Returns the user with the specified username.
    *
    * @param username Username to lookup
    * @return         User with username
    */
  def getUser(username: String)(implicit ec: ExecutionContext): OptionT[Future, SpongeUser] = {
    checkNotNull(username, "null username", "")
    val url = route("/api/users/" + username) + s"?apiKey=$apiKey"
    readUser(this.ws.url(url).withRequestTimeout(timeout).get()).toOption
  }

  private def readUser(response: Future[WSResponse])(
      implicit ec: ExecutionContext
  ): EitherT[Future, String, SpongeUser] = {
    EitherT(
      OptionT(response.map(parseJson(_, Logger)))
        .map { json =>
          val obj = json.as[JsObject]
          if (obj.keys.contains("error"))
            Left((obj \ "error").as[String])
          else
            Right(obj.as[SpongeUser])
        }
        .getOrElse(Left("error.spongeauth.auth"))
        .recover {
          case _: TimeoutException =>
            Left("error.spongeauth.auth")
          case e =>
            Logger.error("An unexpected error occured while handling a response", e)
            Left("error.spongeauth.unexpected")
        }
    )
  }

  private def route(route: String) = this.url + route

}

final class SpongeAuth @Inject()(config: OreConfig, override val ws: WSClient) extends SpongeAuthApi {

  val conf: Configuration = this.config.security

  override val url: String             = this.conf.get[String]("api.url")
  override val apiKey: String          = this.conf.get[String]("api.key")
  override val timeout: FiniteDuration = this.conf.get[FiniteDuration]("api.timeout")

}
