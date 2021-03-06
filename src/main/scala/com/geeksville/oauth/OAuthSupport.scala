package com.geeksville.oauth

import com.geeksville.dapi.model.DBToken
import com.geeksville.dapi.oauth.OAuthStrategy
import grizzled.slf4j.Logging

import scala.util.matching.Regex
import scalaoauth2.provider.DataHandler
import org.scalatra.ScalatraServlet
import com.geeksville.scalatra.{ThreescaleSupport, ControllerExtras}
import scalaoauth2.provider.TokenEndpoint
import scalaoauth2.provider.AuthorizationRequest
import scalaoauth2.provider.GrantHandlerResult
import org.json4s.CustomSerializer
import org.json4s.JsonAST._
import org.json4s.JsonDSL._
import org.json4s._
import scalaoauth2.provider.OAuthError
import scalaoauth2.provider.AuthInfo
import scalaoauth2.provider.ProtectedResource
import org.scalatra.Ok
import org.scalatra.ActionResult
import scalaoauth2.provider.ProtectedResourceRequest
import org.scalatra.BadRequest
import org.scalatra.Unauthorized

/**
 * Replaces our simple threescale based API key checking with alternatively checking for
 * "Authorization: Bearer" style access tokens.
 *
 * OAuth support for scalatra
 */
trait OAuthSupport extends ThreescaleSupport {

  // Convert to the format expected by the the auth lib
  private def requestHeaders = request.headers.map { case (k, v) => k -> Seq(v)}.toMap

  private def requestParams = {
    val r = request.parameters.map { case (k, v) => k -> Seq(v)}.toMap
    debug("params = " + r.mkString(","))
    r
  }

  def authRequest = AuthorizationRequest(requestHeaders, requestParams)

  def protectedResourceRequest = ProtectedResourceRequest(requestHeaders, requestParams)

  def responseAccessToken(r: GrantHandlerResult) = {

    ("token_type" -> r.tokenType) ~
      ("access_token" -> r.accessToken) ~
      ("expires_in" -> r.expiresIn) ~
      ("refresh_token" -> r.refreshToken) ~
      ("scope" -> r.scope)
  }

  // FIXME - use this to return error msgs to client
  def responseOAuthErrorJson(e: OAuthError) = {
    ("error" -> e.errorType) ~
      ("error_description" -> e.description)
  }

  /**
   * Issue access token in DataHandler process and return the response to client.
   *
   * @param dataHandler Implemented DataHander for register access token to your system.
   * @param request Playframework is provided HTTP request interface.
   * @tparam A play.api.mvc.Request has type.
   * @return Request is successful then return JSON to client in OAuth 2.0 format.
   *         Request is failed then return BadRequest or Unauthorized status to client with cause into the JSON.
   */
  def issueAccessToken[A, U](dataHandler: DataHandler[U]): ActionResult = {
    val req = authRequest

    val r = TokenEndpoint.handleRequest(req, dataHandler)
    debug(s"issuingAccessToken: req=$req, result=$r")
    r match {
      case Left(e) if e.statusCode == 400 => BadRequest(responseOAuthErrorJson(e))
      case Left(e) if e.statusCode == 401 => Unauthorized(responseOAuthErrorJson(e))
      case Right(r) =>
        val token = responseAccessToken(r)
        applyNoCache(response)
        Ok(token)
    }
  }

  /**
   * Authorize to already created access token in DataHandler process and return the response to client.
   *
   * @param dataHandler Implemented DataHander for authenticate to your system.
   * @param callback Callback is called when authentication is successful.
   * @param request Playframework is provided HTTP request interface.
   * @tparam A play.api.mvc.Request has type.
   * @return Authentication is successful then the response use your API result.
   *         Authentication is failed then return BadRequest or Unauthorized status to client with cause into the JSON.
   */
  def authorize[A, U](dataHandler: DataHandler[U])(callback: AuthInfo[U] => ActionResult): ActionResult = {
    ProtectedResource.handleRequest(protectedResourceRequest, dataHandler) match {
      case Left(e) if e.statusCode == 400 => BadRequest(responseOAuthErrorJson(e))
      case Left(e) if e.statusCode == 401 => Unauthorized(responseOAuthErrorJson(e))
      case Right(authInfo) => callback(authInfo)
    }
  }

  /// Bearer regexes
  private def bearerAuthHeaders = authHeaders.flatMap { s =>
    s match {
      case OAuthSupport.BearerRegex(key) => Some(key)
      case _ => None
    }
  }

  /**
   * Look for API key in an authorization header, or if not there, then in the query string.
   */
  private def accessToken = {
    val r = bearerAuthHeaders.toSeq.headOption
    //debug(s"AccessToken is $r")
    r
  }

  /**
   * Check for authorization to use serviceId X.  will haltUnauthorized if quota exceeded
   */
  override def requireServiceAuth(metrics: Map[String, String]): Unit = {
    // First check for an oauth Bearer token, if found use that otherwise fall back to threescale
    accessToken match {
      case Some(token) =>
        val dbToken = DBToken.findByAccessToken(token).getOrElse(haltUnauthorized("Invalid access token"))
        if(dbToken.isExpired)
          haltUnauthorized("Access token has expired, you must renew it")

        // check scopes
        val scopes = dbToken.scopes
        debug(s"needed access for ${metrics.keys.mkString(",")} in scopes ${scopes.mkString(",")}")
        metrics.keys.foreach { scope =>
          if(!scopes.contains(scope))
            haltUnauthorized(s"User has not granted $scope permission to this application")
        }

        // Make sure that 3scale is also okay with the app still (and log app usage)
        val apiKey = dbToken.clientId
        requireThreescaleAuth(apiKey, metrics)

        // If we made it this far SUCCESS!  Store the user record someplace scentry can find it
        OAuthStrategy.setOAuthUser(request, dbToken.myUser)

      case None =>
        super.requireServiceAuth(metrics) // threescale must approve
    }
  }
}

object OAuthSupport {
  val BearerRegex = "Bearer (.*)".r

  }
