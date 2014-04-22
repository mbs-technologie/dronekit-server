package com.geeksville.mailgun

import org.scalatra.test.scalatest._
import org.scalatest.FunSuite
import org.scalatest.BeforeAndAfter
import org.json4s.Formats
import org.json4s.DefaultFormats
import org.json4s._
import org.json4s.native.JsonMethods._
import org.json4s.native.Serialization
import java.io.File
import org.scalatra.test.BytesPart
import grizzled.slf4j.Logging
import org.scalatest.GivenWhenThen
import scala.util.Random
import com.geeksville.flight.Location
import com.geeksville.util.Using._

class MailgunTests extends FunSuite with Logging with GivenWhenThen {

  test("mailgun") {
    using(new MailgunClient()) { client =>
      client.sendTo("kevin@3drobotics.com", "kevin@3drobotics.com", "Mailgun test", "This is the body")
      //println(pretty(render(GeoJSON.makePoint(p))))
    }
  }

}