/*
 * Copyright 2014-2017 Netflix, Inc.
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
package com.netflix.atlas.akka

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.testkit.RouteTestTimeout
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.netflix.atlas.json.Json
import com.netflix.iep.service.DefaultClassFactory
import com.typesafe.config.ConfigFactory
import org.scalatest.FunSuite


class RequestHandlerSuite extends FunSuite with ScalatestRouteTest {

  import scala.concurrent.duration._
  implicit val routeTestTimeout = RouteTestTimeout(5.second)

  private val config = ConfigFactory.parseString(
    """
      |atlas.akka.api-endpoints = [
      |  "com.netflix.atlas.akka.TestApi"
      |]
    """.stripMargin)

  private val handler = new RequestHandler(config, new DefaultClassFactory())
  private val routes = handler.routes

  test("/not-found") {
    Get("/not-found") ~> routes ~> check {
      assert(response.status === StatusCodes.NotFound)
    }
  }

  test("/ok") {
    Get("/ok") ~> routes ~> check {
      assert(response.status === StatusCodes.OK)
    }
  }

  test("cors preflight") {
    Options("/api/v2/ip") ~> routes ~> check {
      assert(response.status === StatusCodes.OK)
    }
  }

  test("cors preflight has cors headers") {
    val header = Origin(HttpOrigin("http://localhost"))
    Options("/api/v2/ip").addHeader(header) ~> routes ~> check {
      assert(response.status === StatusCodes.OK)
      assert(response.headers.nonEmpty)
      response.headers.foreach {
        case `Access-Control-Allow-Origin`(v) =>
          assert("http://localhost" === v.toString)
        case `Access-Control-Allow-Methods`(vs) =>
          assert("GET,PATCH,POST,PUT,DELETE" === vs.map(_.name()).mkString(","))
        case `Access-Control-Max-Age`(age) =>
          assert(age === 600)
        case h if h.is("vary") =>
          assert(h.value === "Origin")
        case h =>
          fail(s"unexpected header: $h")
      }
    }
  }

  private def gzip(data: Array[Byte]): Array[Byte] = {
    val baos = new ByteArrayOutputStream
    val out = new GZIPOutputStream(baos)
    out.write(data)
    out.close()
    baos.toByteArray
  }

  private def gzip(s: String): Array[Byte] = gzip(s.getBytes("UTF-8"))

  private val gzipHeader = `Content-Encoding`(HttpEncodings.gzip)

  test("/jsonparse") {
    Post("/jsonparse", "\"foo\"") ~> routes ~> check {
      assert(response.status === StatusCodes.OK)
      assert(responseAs[String] === "foo")
    }
  }

  test("/jsonparse with smile content") {
    val content = HttpEntity(
      CustomMediaTypes.`application/x-jackson-smile`.toContentType,
      Json.smileEncode("foo"))
    Post("/jsonparse", content) ~> routes ~> check {
      assert(response.status === StatusCodes.OK)
      assert(responseAs[String] === "foo")
    }
  }

  test("/jsonparse with smile but wrong content-type") {
    val content = HttpEntity(Json.smileEncode("foo"))
    Post("/jsonparse", content) ~> routes ~> check {
      assert(response.status === StatusCodes.BadRequest)
    }
  }

  test("/jsonparse with gzipped request") {
    val content = HttpEntity(gzip("\"foo\""))
    Post("/jsonparse", content).addHeader(gzipHeader) ~> routes ~> check {
      assert(response.status === StatusCodes.OK)
      assert(responseAs[String] === "foo")
    }
  }

  test("/jsonparse with smile content and gzipped") {
    val content = HttpEntity(
      CustomMediaTypes.`application/x-jackson-smile`.toContentType,
      gzip(Json.smileEncode("foo")))
    Post("/jsonparse", content).addHeader(gzipHeader) ~> routes ~> check {
      assert(response.status === StatusCodes.OK)
      assert(responseAs[String] === "foo")
    }
  }

  test("/chunked with wrong method") {
    Post("/chunked") ~> routes ~> check {
      assert(response.status === StatusCodes.MethodNotAllowed)
    }
  }
}
