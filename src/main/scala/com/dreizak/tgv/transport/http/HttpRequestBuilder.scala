package com.dreizak.tgv.transport.http

import com.ning.http.client.Realm.AuthScheme
import scala.concurrent.duration.FiniteDuration
import scala.Some.apply
import com.ning.http.client.AsyncHttpClient
import com.ning.http.client.PerRequestConfig
import com.ning.http.client.Realm.RealmBuilder
import com.dreizak.tgv.transport.backoff.BackoffStrategy
import com.dreizak.tgv.transport.RetryStrategy

trait SignatureCalculator {
  /**
   * Sign it.
   */
  def sign(request: HttpRequest)
}

/**
 * A request builder for requests to be submitted to a [[com.dreizak.tgv.transport.http.HttpTransport]].
 *
 * TODO: support passing a request body (both iteratee/enumerator- and stream-based)
 * TODO: cookie support
 */
case class HttpRequestBuilder private[http] (private val transport: HttpTransport,
                                             private val nativeBuilder: AsyncHttpClient#BoundRequestBuilder,
                                             val url: String,
                                             private val headers: Map[String, Seq[String]] = Map(),
                                             private val queryString: Map[String, Seq[String]] = Map(),
                                             private val backoffStrategy: Option[BackoffStrategy] = None,
                                             private val retryStrategy: Option[RetryStrategy] = None,
                                             private val calc: Option[SignatureCalculator] = None,
                                             private val auth: Option[(String, String, AuthScheme)] = None,
                                             private val _followRedirects: Option[Boolean] = None,
                                             private val timeout: Option[Int] = None,
                                             private val virtualHost: Option[String] = None) {

  def sign(calc: SignatureCalculator): HttpRequestBuilder = copy(calc = Some(calc))

  def withUrl(url: String): HttpRequestBuilder = copy(url = url)

  def withAuth(username: String, password: String, scheme: AuthScheme): HttpRequestBuilder =
    copy(auth = Some((username, password, scheme)))

  def withHeaders(hdrs: (String, String)*): HttpRequestBuilder = {
    val headers = hdrs.foldLeft(this.headers)((m, hdr) =>
      if (m.contains(hdr._1)) m.updated(hdr._1, m(hdr._1) :+ hdr._2)
      else (m + (hdr._1 -> Seq(hdr._2)))
    )
    copy(headers = headers)
  }

  // TODO: document that this replaces whatever is currently stored under `key` while `addQueryString` adds
  // additional values
  def withQueryString(parameters: (String, String)*): HttpRequestBuilder =
    copy(queryString = parameters.foldLeft(queryString) {
      case (m, (k, v)) => m + (k -> Seq(v))
    })

  def addQueryString(parameters: (String, String)*): HttpRequestBuilder =
    copy(queryString = parameters.foldLeft(queryString) {
      case (m, (k, v)) => m + (k -> (v +: m.get(k).getOrElse(Nil)))
    })

  def withFollowRedirects(follow: Boolean): HttpRequestBuilder =
    copy(_followRedirects = Some(follow))

  def followRedirects(): HttpRequestBuilder =
    copy(_followRedirects = Some(true))

  def withTimeout(timeout: Int): HttpRequestBuilder =
    copy(timeout = Some(timeout))

  def withTimeout(duration: FiniteDuration): HttpRequestBuilder =
    copy(timeout = Some(duration.toMillis.toInt))

  def withVirtualHost(vh: String): HttpRequestBuilder =
    copy(virtualHost = Some(vh))

  def withBackoffStrategy(strategy: BackoffStrategy): HttpRequestBuilder =
    copy(backoffStrategy = Some(strategy))

  def withRetryStrategy(strategy: RetryStrategy): HttpRequestBuilder =
    copy(retryStrategy = Some(strategy))

  def build() = {
    nativeBuilder.setUrl(url)
    headers.foreach(header => header._2.
      foreach(value =>
        nativeBuilder.addHeader(header._1, value)
      ))
    for ((key, values) <- queryString; value <- values) {
      nativeBuilder.addQueryParameter(key, value)
    }
    _followRedirects.map(nativeBuilder.setFollowRedirects(_))
    timeout.map { t: Int =>
      val config = new PerRequestConfig()
      config.setRequestTimeoutInMs(t)
      nativeBuilder.setPerRequestConfig(config)
    }
    virtualHost.map { v => nativeBuilder.setVirtualHost(v) }
    auth.map {
      case (username, password, scheme) =>
        nativeBuilder.setRealm(new RealmBuilder()
          .setScheme(scheme)
          .setPrincipal(username)
          .setPassword(password)
          .setUsePreemptiveAuth(true).
          build())
    }

    new HttpRequest(transport, backoffStrategy, retryStrategy, nativeBuilder.build())
  }
}