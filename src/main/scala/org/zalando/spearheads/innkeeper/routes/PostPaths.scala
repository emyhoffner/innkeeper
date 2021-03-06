package org.zalando.spearheads.innkeeper.routes

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.google.inject.Inject
import org.slf4j.LoggerFactory
import org.zalando.spearheads.innkeeper.Rejections.{DuplicatePathUriHostRejection, UnmarshallRejection}
import org.zalando.spearheads.innkeeper.ValidationDirectives
import org.zalando.spearheads.innkeeper.api.JsonProtocols._
import org.zalando.spearheads.innkeeper.api.{PathIn, TeamName, UserName}
import org.zalando.spearheads.innkeeper.metrics.RouteMetrics
import org.zalando.spearheads.innkeeper.oauth.OAuthDirectives._
import org.zalando.spearheads.innkeeper.oauth.{AuthenticatedUser, Scopes}
import org.zalando.spearheads.innkeeper.services.ServiceResult.DuplicatePathUriHost
import org.zalando.spearheads.innkeeper.services.{PathsService, ServiceResult}
import org.zalando.spearheads.innkeeper.services.team.TeamService

import scala.concurrent.ExecutionContext
import scala.util.Success

class PostPaths @Inject() (
    pathsService: PathsService,
    metrics: RouteMetrics,
    scopes: Scopes,
    validationDirectives: ValidationDirectives,
    implicit val teamService: TeamService,
    implicit val executionContext: ExecutionContext) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def apply(authenticatedUser: AuthenticatedUser, token: String): Route = {
    post {
      val reqDesc = "post /paths"
      logger.info(s"try to $reqDesc")

      entity(as[PathIn]) { path =>
        logger.info(s"We try to $reqDesc unmarshalled path $path")

        team(authenticatedUser, token, "path") { team =>
          logger.info(s"post /paths team $team")

          hasOneOfTheScopes(authenticatedUser, reqDesc, scopes.WRITE) {
            logger.debug(s"post /paths non-admin team $team")

            validationDirectives.validatePath(path, team, reqDesc, isAdmin = false) {
              val ownedByTeam = TeamName(team.name)
              val createdBy = UserName(authenticatedUser.username)

              savePathRoute(path, ownedByTeam, createdBy, reqDesc)
            }
          } ~ hasAdminAuthorization(authenticatedUser, team, reqDesc, scopes)(teamService) {
            logger.debug(s"post /paths admin team $team")

            validationDirectives.validatePath(path, team, reqDesc, isAdmin = true) {
              val createdBy = UserName(authenticatedUser.username)
              val ownedByTeam = path.ownedByTeam.getOrElse(TeamName(team.name))

              savePathRoute(path, ownedByTeam, createdBy, reqDesc)
            }
          }
        }
      } ~ {
        reject(UnmarshallRejection(reqDesc))
      }
    }
  }

  private def savePathRoute(path: PathIn, ownedByTeam: TeamName, createdBy: UserName, reqDesc: String): Route = {
    metrics.postPaths.time {
      logger.debug(s"$reqDesc savePath")
      onComplete(pathsService.create(path, ownedByTeam, createdBy)) {
        case Success(ServiceResult.Success(pathOut))                 => complete(pathOut)
        case Success(ServiceResult.Failure(DuplicatePathUriHost(_))) => reject(DuplicatePathUriHostRejection(reqDesc))
        case _                                                       => reject
      }
    }
  }
}
