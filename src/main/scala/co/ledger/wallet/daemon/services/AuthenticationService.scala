package co.ledger.wallet.daemon.services

import java.nio.charset.StandardCharsets
import java.util.Date

import javax.inject.{Inject, Singleton}
import co.ledger.wallet.daemon.configurations.DaemonConfiguration
import co.ledger.wallet.daemon.database.DaemonCache
import co.ledger.wallet.daemon.database.DefaultDaemonCache.User
import co.ledger.wallet.daemon.exceptions.UserNotFoundException
import co.ledger.wallet.daemon.services.AuthenticationService.{AuthenticationFailedException, AuthentifiedUserContext}
import co.ledger.wallet.daemon.utils.HexUtils
import co.ledger.wallet.daemon.utils.Utils._
import com.twitter.finagle.http.Request
import com.twitter.util.Future
import org.bitcoinj.core.Sha256Hash

import scala.concurrent.ExecutionContext

@Singleton
class AuthenticationService @Inject()(daemonCache: DaemonCache, ecdsa: ECDSAService) extends DaemonService {
  import co.ledger.wallet.daemon.services.AuthenticationService.AuthContextContext._

  def authorize(request: Request)(implicit ec: ExecutionContext): Future[Unit] = {
    attemptAuthorize(request).rescue({
      case ex: UserNotFoundException =>
        if (DaemonConfiguration.isWhiteListDisabled) {
          daemonCache.createUser(HexUtils.valueOf(request.authContext.pubKey), 0).asTwitter().flatMap({ _ =>
            attemptAuthorize(request)
          })
        } else {
          Future.exception(AuthenticationFailedException(ex.getMessage))
        }
      case error => throw error
    })
  }

  private def attemptAuthorize(request: Request)(implicit ec: ExecutionContext): Future[Unit] = {
    try {
      val pubKey = request.authContext.pubKey
      val pubKeyAsString = HexUtils.valueOf(pubKey)
      daemonCache.getUser(pubKeyAsString) map {
        case None =>
          throw UserNotFoundException(pubKeyAsString)
        case Some(user) =>
          val loginAtAsLong = request.authContext.time
          val loginAt = new Date(loginAtAsLong * 1000)
          val currentTime = new Date()
          if ( Math.abs(currentTime.getTime - loginAt.getTime) > DaemonConfiguration.authTokenDuration) {
            throw AuthenticationFailedException("Authentication token expired")
          }
          val msg = Sha256Hash.hash(s"LWD: $loginAtAsLong\n".getBytes(StandardCharsets.UTF_8))
          val signed = request.authContext.signedMessage
          if(!ecdsa.verify(msg, signed, pubKey)) {
            throw AuthenticationFailedException("User not authorized")
          }
          AuthentifiedUserContext.setUser(request, user)
      } asTwitter()
    } catch {
      case _: IllegalStateException => throw AuthenticationFailedException("Missing authorization header")
    }
  }

}

object AuthenticationService {

  case class AuthenticationFailedException(msg: String) extends Exception(msg)
  case class AuthContext(pubKey: Array[Byte], time: Long, signedMessage: Array[Byte])
  object AuthContextContext {
    private val AuthContextField = Request.Schema.newField[AuthContext]()
    implicit class AuthContextContextSyntax(val request: Request) extends AnyVal {
      def authContext: AuthContext = request.ctx(AuthContextField)
    }
    def setContext(request: Request, context: AuthContext): Unit = {
      request.ctx.update(AuthContextField, context)
    }
  }

  case class AuthentifiedUser(get: User)
  object AuthentifiedUserContext {
    private val UserField = Request.Schema.newField[AuthentifiedUser]()

    implicit class UserContextSyntax(val request: Request) extends AnyVal {
      def user: AuthentifiedUser = request.ctx(UserField)
    }
    def setUser(request: Request, user: User): Unit = request.ctx.update[AuthentifiedUser](UserField, AuthentifiedUser(user))
  }
}
