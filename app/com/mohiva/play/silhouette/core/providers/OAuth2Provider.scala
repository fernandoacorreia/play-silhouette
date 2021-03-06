/**
 * Original work: SecureSocial (https://github.com/jaliss/securesocial)
 * Copyright 2013 Jorge Aliss (jaliss at gmail dot com) - twitter: @jaliss
 *
 * Derivative work: Silhouette (https://github.com/mohiva/play-silhouette)
 * Modifications Copyright 2014 Mohiva Organisation (license at mohiva dot com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.mohiva.play.silhouette.core.providers

import java.net.URLEncoder._
import java.util.UUID
import play.api.mvc.{ SimpleResult, RequestHeader, Results }
import play.api.libs.ws.Response
import play.api.libs.json._
import play.api.libs.functional.syntax._
import scala.util.{ Failure, Success, Try }
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import com.mohiva.play.silhouette.core.utils.{ HTTPLayer, CacheLayer }
import com.mohiva.play.silhouette.core.services.AuthInfo
import com.mohiva.play.silhouette.core.exceptions._
import com.mohiva.play.silhouette.core._
import OAuth2Provider._

/**
 * The Oauth2 details.
 *
 * @param accessToken The access token.
 * @param tokenType The token type.
 * @param expiresIn The number of seconds before the token expires.
 * @param refreshToken The refresh token.
 */
case class OAuth2Info(
  accessToken: String,
  tokenType: Option[String] = None,
  expiresIn: Option[Int] = None,
  refreshToken: Option[String] = None) extends AuthInfo

/**
 * The Oauth2 companion object.
 */
object OAuth2Info {

  /**
   * Converts the JSON into a [[com.mohiva.play.silhouette.core.providers.OAuth2Info]] object.
   */
  implicit val infoReads = (
    (__ \ AccessToken).read[String] and
    (__ \ TokenType).readNullable[String] and
    (__ \ ExpiresIn).readNullable[Int] and
    (__ \ RefreshToken).readNullable[String]
  )(OAuth2Info.apply _)
}

/**
 * Base class for all OAuth2 providers.
 *
 * @param settings The provider settings.
 * @param cacheLayer The cache layer implementation.
 * @param httpLayer The HTTP layer implementation.
 */
abstract class OAuth2Provider(
  settings: OAuth2Settings,
  cacheLayer: CacheLayer,
  httpLayer: HTTPLayer)
    extends SocialProvider[OAuth2Info]
    with Logger {

  /**
   * A list with headers to send to the API.
   */
  protected val headers: Seq[(String, String)] = Seq()

  /**
   * Starts the authentication process.
   *
   * @param request The request header.
   * @return Either a Result or the auth info from the provider.
   */
  protected def doAuth()(implicit request: RequestHeader): Future[Either[SimpleResult, OAuth2Info]] = {
    logger.debug("[Silhouette][%s] Query string: %s".format(id, request.rawQueryString))
    request.queryString.get(Error).flatMap(_.headOption).map {
      case e @ AccessDenied => new AccessDeniedException(AuthorizationError.format(id, e))
      case e => new AuthenticationException(AuthorizationError.format(id, e))
    } match {
      case Some(throwable) => Future.failed(throwable)
      case None => request.queryString.get(Code).flatMap(_.headOption) match {
        // We're being redirected back from the authorization server with the access code
        case Some(code) => cachedState.map { case (cacheID, cachedState) => cachedState == requestState.get }.flatMap {
          case false => throw new AuthenticationException(StateIsNotEqual.format(id))
          case true => getAccessToken(code).map(oauth2Info => Right(oauth2Info))
        }
        // There's no code in the request, this is the first step in the OAuth flow
        case None =>
          val state = UUID.randomUUID().toString
          val cacheID = request.session.get(CacheKey).getOrElse(UUID.randomUUID().toString)
          val params = settings.scope.foldLeft(List(
            (ClientID, settings.clientID),
            (RedirectURI, settings.redirectURL),
            (ResponseType, Code),
            (State, state)) ++ settings.authorizationParams.toList) {
            case (p, s) => (Scope, s) :: p
          }
          val encodedParams = params.map { p => encode(p._1, "UTF-8") + "=" + encode(p._2, "UTF-8") }
          val url = settings.authorizationURL + encodedParams.mkString("?", "&", "")
          val redirect = Results.Redirect(url).withSession(request.session + (CacheKey -> cacheID))
          logger.debug("[Silhouette][%s] Use authorization URL: %s".format(id, settings.authorizationURL))
          logger.debug("[Silhouette][%s] Redirecting to: %s".format(id, url))
          cacheLayer.set(cacheID, state, CacheExpiration)
          Future.successful(Left(redirect))
      }
    }
  }

  /**
   * Gets the access token.
   *
   * @param code The access code.
   * @return The info containing the access token.
   */
  protected def getAccessToken(code: String): Future[OAuth2Info] = {
    httpLayer.url(settings.accessTokenURL).withHeaders(headers: _*).post(Map(
      ClientID -> Seq(settings.clientID),
      ClientSecret -> Seq(settings.clientSecret),
      GrantType -> Seq(AuthorizationCode),
      Code -> Seq(code),
      RedirectURI -> Seq(settings.redirectURL)) ++ settings.accessTokenParams.mapValues(Seq(_))).flatMap { response =>
      logger.debug("[Silhouette][%s] Access token response: [%s]".format(id, response.body))
      buildInfo(response).asFuture
    }
  }

  /**
   * Builds the OAuth2 info.
   *
   * @param response The response from the provider.
   * @return The OAuth2 info on success, otherwise an failure.
   */
  protected def buildInfo(response: Response): Try[OAuth2Info] = {
    response.json.validate[OAuth2Info].asEither.fold(
      error => Failure(new AuthenticationException(InvalidResponseFormat.format(id, error))),
      info => Success(info)
    )
  }

  /**
   * Gets the cached state if it's stored in cache.
   *
   * @param request The request header.
   * @return A tuple contains the cache ID with the cached state on success, otherwise an failure.
   */
  private def cachedState(implicit request: RequestHeader): Future[(String, String)] = {
    request.session.get(CacheKey) match {
      case Some(cacheID) => cacheLayer.get[String](cacheID).map {
        case Some(state) => cacheID -> state
        case _ => throw new AuthenticationException(CachedStateDoesNotExists.format(id, cacheID))
      }
      case _ => Future.failed(new AuthenticationException(CacheKeyNotInSession.format(id, CacheKey)))
    }
  }

  /**
   * Gets the state from request.
   *
   * @param request The request header.
   * @return The state from request on success, otherwise an failure.
   */
  private def requestState(implicit request: RequestHeader): Try[String] = {
    request.queryString.get(State).flatMap(_.headOption) match {
      case Some(state) => Success(state)
      case _ => Failure(new AuthenticationException(RequestStateDoesNotExists.format(id)))
    }
  }
}

/**
 * The OAuth2Provider companion object.
 */
object OAuth2Provider {

  /**
   * The error messages.
   */
  val AuthorizationError = "[Silhouette][%s] Authorization server returned error: %s"
  val CacheKeyNotInSession = "[Silhouette][%s] Session doesn't contain cache key: %s"
  val CachedStateDoesNotExists = "[Silhouette][%s] State doesn't exists in cache for cache key: %s"
  val RequestStateDoesNotExists = "[Silhouette][%s] State doesn't exists in query string"
  val StateIsNotEqual = "[Silhouette][%s] State isn't equal"
  val InvalidResponseFormat = "[Silhouette][%s] Invalid response format for accessToken: %s"

  /**
   * The OAuth2 constants.
   */
  val CacheKey = "silhouetteOAuth2Cache"
  val ClientID = "client_id"
  val ClientSecret = "client_secret"
  val RedirectURI = "redirect_uri"
  val Scope = "scope"
  val ResponseType = "response_type"
  val State = "state"
  val GrantType = "grant_type"
  val AuthorizationCode = "authorization_code"
  val AccessToken = "access_token"
  val Error = "error"
  val Code = "code"
  val TokenType = "token_type"
  val ExpiresIn = "expires_in"
  val Expires = "expires"
  val RefreshToken = "refresh_token"
  val AccessDenied = "access_denied"

  /**
   * Cache expiration. Provides sufficient time to log in, but not too much.
   * This is a balance between convenience and security.
   */
  val CacheExpiration = 5 * 60; // 5 minutes
}

/**
 * The OAuth2 settings.
 *
 * @param authorizationURL The authorization URL.
 * @param accessTokenURL The access token URL.
 * @param redirectURL The redirect URL.
 * @param clientID The client ID.
 * @param clientSecret The client secret.
 * @param scope The scope.
 * @param authorizationParams Additional params to add to the authorization request.
 * @param accessTokenParams Additional params to add to the access token request.
 * @param customProperties A map of custom properties for the different providers.
 */
case class OAuth2Settings(
  authorizationURL: String,
  accessTokenURL: String,
  redirectURL: String,
  clientID: String,
  clientSecret: String,
  scope: Option[String] = None,
  authorizationParams: Map[String, String] = Map(),
  accessTokenParams: Map[String, String] = Map(),
  customProperties: Map[String, String] = Map())
