package lila.ws
package netty

import io.netty.buffer.Unpooled
import io.netty.channel.{ ChannelFutureListener, ChannelHandlerContext, SimpleChannelInboundHandler }
import io.netty.handler.codec.http.{
  DefaultFullHttpResponse,
  FullHttpRequest,
  HttpHeaderNames,
  HttpMethod,
  HttpResponseStatus
}

import lila.ws.util.RequestUri

final private class RequestHandler(router: Router, useNginxForwardedIp: Boolean)(using Executor)
    extends SimpleChannelInboundHandler[FullHttpRequest](
      false // do not automatically release req
    ):

  override def channelRead0(ctx: ChannelHandlerContext, req: FullHttpRequest): Unit =
    val ip      = findClientIpAddressOrBreak(ctx, req)
    val request = util.RequestHeader(RequestUri(req.uri), ip, req.headers)
    router(request).foreach:
      case Left(status) =>
        sendErrorResponse(
          ctx,
          DefaultFullHttpResponse(req.protocolVersion(), status, Unpooled.EMPTY_BUFFER)
        )
        req.release()
      case Right(_) if req.method != HttpMethod.GET =>
        val response = DefaultFullHttpResponse(
          req.protocolVersion(),
          HttpResponseStatus.METHOD_NOT_ALLOWED,
          Unpooled.EMPTY_BUFFER
        )
        response.headers.set(HttpHeaderNames.ALLOW, "GET")
        sendErrorResponse(ctx, response)
        req.release()
      case Right(endpoint) =>
        Monitor.mobile.connect(request)
        // Forward to ProtocolHandler with endpoint attribute
        ctx.channel.attr(ProtocolHandler.key.endpoint).set(endpoint)
        ctx.fireChannelRead(req)

  private def sendErrorResponse(ctx: ChannelHandlerContext, response: DefaultFullHttpResponse) =
    val f = ctx.writeAndFlush(response)
    f.addListener(ChannelFutureListener.CLOSE)

  private def findClientIpAddressOrBreak(ctx: ChannelHandlerContext, req: FullHttpRequest): IpAddress =
    if useNginxForwardedIp then
      Option(req.headers.get("X-Forwarded-For"))
        .filter(_.nonEmpty)
        .fold(sys.error("Missing X-Forwarded-For header"))(IpAddress(_))
    else
      IpAddress:
        ctx.channel.remoteAddress
          .asInstanceOf[java.net.InetSocketAddress]
          .getAddress
          .getHostAddress
