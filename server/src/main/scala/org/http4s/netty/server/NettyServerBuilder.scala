package org.http4s.netty.server

import java.net.InetSocketAddress
import cats.Applicative
import cats.effect.{ConcurrentEffect, Resource, Sync}
import cats.implicits._
import com.comcast.ip4s.{IpAddress, IpLiteralSyntax, Port, SocketAddress}
import com.typesafe.netty.http.HttpStreamsServerHandler
import fs2.io.tls.TLSParameters
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel._
import io.netty.channel.epoll.{Epoll, EpollEventLoopGroup, EpollServerSocketChannel}
import io.netty.channel.kqueue.{KQueue, KQueueEventLoopGroup, KQueueServerSocketChannel}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.unix.DomainSocketAddress
import io.netty.handler.codec.http.{HttpRequestDecoder, HttpResponseEncoder}
import io.netty.handler.ssl.SslHandler
import io.netty.handler.timeout.IdleStateHandler
import io.netty.incubator.channel.uring.{IOUring, IOUringEventLoopGroup, IOUringServerSocketChannel}

import javax.net.ssl.{SSLContext, SSLEngine}
import org.http4s.HttpApp
import org.http4s.netty.NettyChannelOptions
import org.http4s.server.{Server, ServiceErrorHandler}

import java.nio.file.Path
import scala.collection.immutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.reflect.ClassTag

final class NettyServerBuilder[F[_]] private (
    httpApp: HttpApp[F],
    serviceErrorHandler: ServiceErrorHandler[F],
    idleTimeout: Duration,
    eventLoopThreads: Int,
    maxInitialLineLength: Int,
    maxHeaderSize: Int,
    maxChunkSize: Int,
    transport: Bind,
    banner: immutable.Seq[String],
    executionContext: ExecutionContext,
    nettyChannelOptions: NettyChannelOptions,
    sslConfig: NettyServerBuilder.SslConfig[F],
    websocketsEnabled: Boolean,
    wsMaxFrameLength: Int
)(implicit F: ConcurrentEffect[F]) {
  private val logger = org.log4s.getLogger
  type Self = NettyServerBuilder[F]

  private def copy(
      httpApp: HttpApp[F] = httpApp,
      serviceErrorHandler: ServiceErrorHandler[F] = serviceErrorHandler,
      idleTimeout: Duration = idleTimeout,
      eventLoopThreads: Int = eventLoopThreads,
      maxInitialLineLength: Int = maxInitialLineLength,
      maxHeaderSize: Int = maxHeaderSize,
      maxChunkSize: Int = maxChunkSize,
      transport: Bind = transport,
      banner: immutable.Seq[String] = banner,
      executionContext: ExecutionContext = executionContext,
      nettyChannelOptions: NettyChannelOptions = nettyChannelOptions,
      sslConfig: NettyServerBuilder.SslConfig[F] = sslConfig,
      websocketsEnabled: Boolean = websocketsEnabled,
      wsMaxFrameLength: Int = wsMaxFrameLength
  ): NettyServerBuilder[F] =
    new NettyServerBuilder[F](
      httpApp,
      serviceErrorHandler,
      idleTimeout,
      eventLoopThreads,
      maxInitialLineLength,
      maxHeaderSize,
      maxChunkSize,
      transport,
      banner,
      executionContext,
      nettyChannelOptions,
      sslConfig,
      websocketsEnabled,
      wsMaxFrameLength
    )

  private def getEventLoop: EventLoopHolder[_ <: ServerChannel] =
    transport match {
      case Bind.Network(address, forceNio) =>
        val resolvedAddress = address.toInetSocketAddress
        if (forceNio) {
          EventLoopHolder[NioServerSocketChannel](
            resolvedAddress,
            new NioEventLoopGroup(eventLoopThreads))
        } else {
          if (IOUring.isAvailable) {
            logger.info("Using IOUring")
            EventLoopHolder[IOUringServerSocketChannel](
              resolvedAddress,
              new IOUringEventLoopGroup(eventLoopThreads))
          } else if (Epoll.isAvailable) {
            logger.info("Using Epoll")
            EventLoopHolder[EpollServerSocketChannel](
              resolvedAddress,
              new EpollEventLoopGroup(eventLoopThreads))
          } else if (KQueue.isAvailable) {
            logger.info("Using KQueue")
            EventLoopHolder[KQueueServerSocketChannel](
              resolvedAddress,
              new KQueueEventLoopGroup(eventLoopThreads))
          } else {
            logger.info("Falling back to NIO EventLoopGroup")
            EventLoopHolder[NioServerSocketChannel](
              resolvedAddress,
              new NioEventLoopGroup(eventLoopThreads))
          }
        }
      case Bind.NamedSocket(path) =>
        if (Epoll.isAvailable) {
          logger.info("Using Epoll")
          EventLoopHolder[EpollServerSocketChannel](
            new DomainSocketAddress(path.toFile),
            new EpollEventLoopGroup(eventLoopThreads))
        } else if (KQueue.isAvailable) {
          logger.info("Using KQueue")
          EventLoopHolder[KQueueServerSocketChannel](
            new DomainSocketAddress(path.toFile),
            new KQueueEventLoopGroup(eventLoopThreads))
        } else {
          val msg = "KQueue or Epoll must be available for named sockets to work"
          logger.warn(msg)
          throw new IllegalStateException(msg)
        }
    }

  def withHttpApp(httpApp: HttpApp[F]): Self = copy(httpApp = httpApp)
  def withExecutionContext(ec: ExecutionContext): Self = copy(executionContext = ec)

  def bindHttp(port: Port = port"8080", host: IpAddress = ip"127.0.0.1"): Self =
    withNativeTransport(SocketAddress(host, port))
  def bindLocal(port: Port): Self = bindHttp(port, ip"127.0.0.1")
  def bindAny(host: IpAddress = ip"127.0.0.1"): Self =
    bindHttp(port"0", host)

  def withNativeTransport(address: SocketAddress[IpAddress]): Self =
    copy(transport = Bind.Network(address, forceNio = false))
  def withNioTransport(address: SocketAddress[IpAddress]): Self =
    copy(transport = Bind.Network(address, forceNio = true))
  def withUnixTransport(socket: Path): Self = copy(transport = Bind.NamedSocket(socket))
  def withoutBanner: Self = copy(banner = Nil)
  def withMaxHeaderSize(size: Int): Self = copy(maxHeaderSize = size)
  def withMaxChunkSize(size: Int): Self = copy(maxChunkSize = size)
  def withMaxInitialLineLength(size: Int): Self = copy(maxInitialLineLength = size)
  def withServiceErrorHandler(handler: ServiceErrorHandler[F]): Self =
    copy(serviceErrorHandler = handler)
  def withNettyChannelOptions(opts: NettyChannelOptions): Self =
    copy(nettyChannelOptions = opts)
  def withWebsockets: Self = copy(websocketsEnabled = true)
  def withoutWebsockets: Self = copy(websocketsEnabled = false)

  /** Configures the server with TLS, using the provided `SSLContext` and `SSLParameters`. */
  def withSslContext(
      sslContext: SSLContext,
      tlsParameters: TLSParameters = TLSParameters.Default): Self =
    copy(sslConfig = new NettyServerBuilder.ContextWithParameters[F](sslContext, tlsParameters))

  def withoutSsl: Self =
    copy(sslConfig = new NettyServerBuilder.NoSsl[F]())

  /** Socket selector threads.
    * @param nThreads
    *   number of selector threads. Use <code>0</code> for netty default
    * @return
    *   an updated builder
    */
  def withEventLoopThreads(nThreads: Int): Self = copy(eventLoopThreads = nThreads)

  def withIdleTimeout(duration: FiniteDuration): Self = copy(idleTimeout = duration)

  private def bind(tlsEngine: Option[SSLEngine]) = {
    val server = new ServerBootstrap()
    val loop = getEventLoop
    val channel = loop
      .configure(server)
      .childHandler(new ChannelInitializer[SocketChannel] {
        override def initChannel(ch: SocketChannel): Unit = {
          val pipeline = ch.pipeline()
          tlsEngine.foreach { engine =>
            pipeline.addLast("ssl", new SslHandler(engine))
          }
          pipeline.addLast(
            "http-decoder",
            new HttpRequestDecoder(maxInitialLineLength, maxHeaderSize, maxChunkSize))
          pipeline.addLast("http-encoder", new HttpResponseEncoder())
          if (idleTimeout.isFinite && idleTimeout.length > 0)
            pipeline.addLast(
              "idle-handler",
              new IdleStateHandler(0, 0, idleTimeout.length, idleTimeout.unit))
          pipeline
            .addLast("serverStreamsHandler", new HttpStreamsServerHandler())
            .addLast(
              "http4s",
              if (websocketsEnabled)
                Http4sNettyHandler
                  .websocket(httpApp, serviceErrorHandler, wsMaxFrameLength, executionContext)
              else Http4sNettyHandler.default(app = httpApp, serviceErrorHandler, executionContext)
            )
          ()
        }
      })
      .bind(loop.address)
      .await()
      .channel()
    Bound(channel.localAddress().asInstanceOf[InetSocketAddress], loop, channel)
  }

  def resource: Resource[F, Server] =
    for {
      maybeEngine <- Resource.eval(createSSLEngine)
      bound <- Resource.make(Sync[F].delay(bind(maybeEngine))) {
        case Bound(address, loop, channel) =>
          Sync[F].delay {
            channel.close().awaitUninterruptibly()
            loop.eventLoop.shutdownGracefully()
            logger.info(s"All channels shut down. Server bound at ${address} shut down gracefully")
          }
      }
    } yield {
      val server = new Server {
        override def address: InetSocketAddress = bound.address

        override def isSecure: Boolean = sslConfig.isSecure
      }
      banner.foreach(logger.info(_))
      logger.info(s"Started Http4s Netty Server at ${server.baseUri}")
      server
    }

  def allocated: F[(Server, F[Unit])] = resource.allocated
  def stream = fs2.Stream.resource(resource)

  private def createSSLEngine =
    sslConfig.makeContext.flatMap(maybeCtx =>
      F.delay(maybeCtx.map { ctx =>
        val engine = ctx.createSSLEngine()
        engine.setUseClientMode(false)
        sslConfig.configureEngine(engine)
        engine
      }))

  case class EventLoopHolder[A <: ServerChannel](
      address: java.net.SocketAddress,
      eventLoop: MultithreadEventLoopGroup)(implicit
      classTag: ClassTag[A]
  ) {
    def shutdown(): Unit = {
      eventLoop.shutdownGracefully()
      ()
    }
    def runtimeClass: Class[A] = classTag.runtimeClass.asInstanceOf[Class[A]]
    def configure(bootstrap: ServerBootstrap) = {
      val configured = bootstrap
        .group(eventLoop)
        .channel(runtimeClass)
        .childOption(ChannelOption.AUTO_READ, java.lang.Boolean.FALSE)
      nettyChannelOptions.foldLeft(configured) { case (c, (opt, optV)) => c.childOption(opt, optV) }
    }

  }
  case class Bound(
      address: InetSocketAddress,
      holder: EventLoopHolder[_ <: ServerChannel],
      channel: Channel)
}

object NettyServerBuilder {
  private val DefaultWSMaxFrameLength = 65536

  def apply[F[_]](implicit F: ConcurrentEffect[F]): NettyServerBuilder[F] =
    new NettyServerBuilder[F](
      httpApp = HttpApp.notFound[F],
      serviceErrorHandler = org.http4s.server.DefaultServiceErrorHandler[F],
      idleTimeout = org.http4s.server.defaults.IdleTimeout,
      eventLoopThreads = 0, // let netty decide
      maxInitialLineLength = 4096,
      maxHeaderSize = 8192,
      maxChunkSize = 8192,
      transport = Bind.Network(
        SocketAddress(ip"127.0.0.1", port"8080"),
        forceNio = false
      ),
      banner = org.http4s.server.defaults.Banner,
      executionContext = ExecutionContext.global,
      nettyChannelOptions = NettyChannelOptions.empty,
      sslConfig = new NettyServerBuilder.NoSsl[F],
      websocketsEnabled = false,
      wsMaxFrameLength = DefaultWSMaxFrameLength
    )

  private sealed trait SslConfig[F[_]] {
    def makeContext: F[Option[SSLContext]]
    def configureEngine(sslEngine: SSLEngine): Unit
    def isSecure: Boolean
  }

  private class ContextWithParameters[F[_]](sslContext: SSLContext, tlsParameters: TLSParameters)(
      implicit F: Applicative[F])
      extends SslConfig[F] {
    def makeContext = F.pure(sslContext.some)
    def configureEngine(engine: SSLEngine) = engine.setSSLParameters(tlsParameters.toSSLParameters)
    def isSecure = true
  }

  private class NoSsl[F[_]]()(implicit F: Applicative[F]) extends SslConfig[F] {
    def makeContext = F.pure(None)
    def configureEngine(engine: SSLEngine) = {
      val _ = engine
      ()
    }
    def isSecure = false
  }
}
