package org.zalando.spearheads.innkeeper.services

import java.time.LocalDateTime

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FunSpec, Matchers}
import org.zalando.spearheads.innkeeper.FakeDatabasePublisher
import org.zalando.spearheads.innkeeper.api.{EskipRoute, EskipRouteWrapper, Filter, NameWithStringArgs, NewRoute, NumericArg, Predicate, RegexArg, RouteName, RouteOut, StringArg, TeamName, UserName}
import org.zalando.spearheads.innkeeper.dao.{PathRow, RouteRow, RoutesRepo}

import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext
import org.zalando.spearheads.innkeeper.api.JsonProtocols._
import spray.json.pimpAny

class EskipRouteServiceSpec extends FunSpec with Matchers with MockFactory with ScalaFutures {

  implicit val executionContext = ExecutionContext.global
  implicit val actorSystem = ActorSystem()
  implicit val materializer = ActorMaterializer()
  val routesRepo = mock[RoutesRepo]
  val routeToEskipTransformer = mock[RouteToEskipTransformer]
  val eskipRouteService = new EskipRouteService(routesRepo, routeToEskipTransformer)

  describe("route to eskip") {

    describe("#currentEskipRoutes") {
      describe("when the common filters are enabled") {

        it ("should return the correct current routes") {

          (routesRepo.selectActiveRoutesWithPath _)
            .expects(currentTime)
            .returning(FakeDatabasePublisher(Seq((routeRow, pathRow))))

          (routeToEskipTransformer.transform _)
            .expects(transformerContext)
            .returning(eskipRoute)

          val result = eskipRouteService.currentEskipRoutes(currentTime)
            .runWith(Sink.head).futureValue

          verifyRoute(result)
        }
      }

      describe("when the commond filters are not enabled") {
        it ("should return the correct current routes") {
          (routesRepo.selectActiveRoutesWithPath _)
            .expects(currentTime)
            .returning(FakeDatabasePublisher(Seq((routeRow.copy(usesCommonFilters = false), pathRow))))

          val eskipRouteWithoutCommonFilters =
            eskipRoute.copy(
              prependedFilters = Seq(),
              appendedFilters = Seq())

          (routeToEskipTransformer.transform _)
            .expects(transformerContext.copy(useCommonFilters = false))
            .returning(eskipRouteWithoutCommonFilters)

          val result = eskipRouteService.currentEskipRoutes(currentTime)
            .runWith(Sink.head).futureValue

          result.name should be(RouteName(routeName))
          result.eskip should
            be("""myRoute: somePredicate("Hello",123) && somePredicate1(/^Hello$/,123)
                                   | -> someFilter("Hello",123)
                                   | -> someFilter1(/^Hello$/,123)
                                   | -> "endpoint.my.com"""".stripMargin)

          result.createdAt should be(createdAt)
          result.deletedAt should be(None)
        }
      }
    }
  }

  describe("#findModifiedSince") {
    it("should find the right route") {
      val now = LocalDateTime.now()

      (routesRepo.selectModifiedSince _).expects(createdAt, now).returning {
        FakeDatabasePublisher[(RouteRow, PathRow)](Seq((routeRow, pathRow)))
      }

      (routeToEskipTransformer.transform _)
        .expects(transformerContext)
        .returning(eskipRoute)

      val result = eskipRouteService.findModifiedSince(createdAt, now).runWith(Sink.head).futureValue

      verifyRoute(result)
    }
  }

  private def verifyRoute(result: EskipRouteWrapper) = {
    result.name should be(RouteName(routeName))
    result.eskip should
      be(

        """myRoute: somePredicate("Hello",123) && somePredicate1(/^Hello$/,123)
          | -> prependedFirst("hello")
          | -> prependedSecond(1.5)
          | -> someFilter("Hello",123)
          | -> someFilter1(/^Hello$/,123)
          | -> appendedFirst()
          | -> appendedSecond(0.8)
          | -> "endpoint.my.com"""".stripMargin)

    result.createdAt should
      be(createdAt)
    result.
      deletedAt should be(None)
  }

  val currentTime = LocalDateTime.now()

  // route
  val routeName = "myRoute"
  val createdAt = LocalDateTime.of(2015, 10, 10, 10, 10, 10)
  // path
  val pathUri = "/the-uri"
  val hostIds = Seq(1L, 2L, 3L)
  val pathId = 6L

  val newRoute = NewRoute(
    predicates = Some(Seq(
      Predicate("somePredicate", Seq(StringArg("Hello"), NumericArg("123"))),
      Predicate("somePredicate1", Seq(RegexArg("Hello"), NumericArg("123"))))),
    filters = Some(Seq(
      Filter("someFilter", Seq(StringArg("Hello"), NumericArg("123"))),
      Filter("someFilter1", Seq(RegexArg("Hello"), NumericArg("123")))))
  )

  val routeRow = RouteRow(
    id = Some(1L),
    pathId = pathId,
    name = routeName,
    routeJson = newRoute.toJson.compactPrint,
    activateAt = LocalDateTime.now(),
    usesCommonFilters = true,
    createdBy = "user",
    createdAt = createdAt)

  val pathRow = PathRow(
    id = Some(pathId),
    uri = pathUri,
    hostIds = hostIds,
    ownedByTeam = "team",
    createdBy = "user",
    createdAt = LocalDateTime.now())

  val routeOut = RouteOut(
    1,
    1L,
    RouteName(routeName),
    newRoute,
    createdAt,
    activateAt = LocalDateTime.of(2015, 10, 10, 10, 10, 11),
    UserName("user"),
    usesCommonFilters = false,
    disableAt = Some(LocalDateTime.of(2015, 11, 11, 11, 11, 11))
  )

  val eskipRoute = EskipRoute(
    name = routeName,
    predicates = Seq(
      NameWithStringArgs("somePredicate", Seq(""""Hello"""", "123")),
      NameWithStringArgs("somePredicate1", Seq("/^Hello$/", "123"))),
    filters = Seq(
      NameWithStringArgs("someFilter", Seq(""""Hello"""", "123")),
      NameWithStringArgs("someFilter1", Seq("/^Hello$/", "123"))
    ),
    prependedFilters = Seq("""prependedFirst("hello")""", "prependedSecond(1.5)"),
    appendedFilters = Seq("appendedFirst()", "appendedSecond(0.8)"),
    endpoint = "\"endpoint.my.com\"")

  val transformerContext = RouteToEskipTransformerContext(
    routeName = routeName,
    pathUri = pathUri,
    hostIds = hostIds,
    useCommonFilters = true,
    route = newRoute
  )
}

