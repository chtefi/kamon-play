package kamon.play.instrumentation

import io.netty.handler.codec.http.{HttpRequest, HttpResponse}
import kamon.Kamon
import kamon.context.Context
import kamon.play.OperationNameFilter
import kamon.trace.Span
import kamon.util.CallingThreadExecutionContext
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation._
import play.api.mvc.EssentialFilter

import scala.concurrent.Future

@Aspect
class NettyRequestHandlerInstrumentation {

  private lazy val filter: EssentialFilter = new OperationNameFilter()

  @Around("execution(* play.core.server.netty.PlayRequestHandler.handle(..)) && args(*, request)")
  def onHandle(pjp: ProceedingJoinPoint, request: HttpRequest): Any = {
    val incomingContext = decodeContext(request)
    val serverSpan = Kamon.buildSpan("unknown-operation")
      .asChildOf(incomingContext.get(Span.ContextKey))
      .withMetricTag("span.kind", "server")
      .withTag("component", "play.server.netty")
      .withTag("http.method", request.method().name())
      .withTag("http.url", request.uri())
      .start()

    val responseFuture = Kamon.withContext(incomingContext.withKey(Span.ContextKey, serverSpan)) {
      pjp.proceed().asInstanceOf[Future[HttpResponse]]
    }

    responseFuture.transform(
      s = response => {
        val responseStatus = response.status()
        serverSpan.tag("http.status_code", responseStatus.code())

        if(isError(responseStatus.code))
          serverSpan.addError(responseStatus.reasonPhrase())

        if(responseStatus.code == StatusCodes.NotFound)
          serverSpan.setOperationName("not-found")

        serverSpan.finish()
        response
      },
      f = error => {
        serverSpan.addError("error.object", error)
        serverSpan.finish()
        error
      }
    )(CallingThreadExecutionContext)
  }

  @Around("call(* play.api.http.HttpFilters.filters(..))")
  def filters(pjp: ProceedingJoinPoint): Any = {
    filter +: pjp.proceed().asInstanceOf[Seq[EssentialFilter]]
  }
}
