package com.datamountaineer.connect.tools

import AppCommand._
import org.scalatest.{FunSuite, Matchers}
import org.scalamock.scalatest.MockFactory
import spray.json._
import spray.json.{JsonReader, DefaultJsonProtocol}
import DefaultJsonProtocol._

import scala.util.{Success, Try}

class MainCliUnitTests extends FunSuite with Matchers with MockFactory {
  def split(s:String) = s.split(" ")

  test("Valid program arguments are parsed correctly") {
    Cli.parseProgramArgs(split("ps")) shouldEqual Some(Arguments(LIST_ACTIVE, Defaults.BaseUrl, None))
    Cli.parseProgramArgs(split("ps -e my_endpoint")) shouldEqual Some(Arguments(LIST_ACTIVE, "my_endpoint", None))
    Cli.parseProgramArgs(split("rm killit -e my_endpoint")) shouldEqual Some(Arguments(DELETE, "my_endpoint", Some("killit")))
    Cli.parseProgramArgs(split("get getit")) shouldEqual Some(Arguments(GET, Defaults.BaseUrl, Some("getit")))
    Cli.parseProgramArgs(split("create createit")) shouldEqual Some(Arguments(CREATE, Defaults.BaseUrl, Some("createit")))
    Cli.parseProgramArgs(split("run runit")) shouldEqual Some(Arguments(RUN, Defaults.BaseUrl, Some("runit")))
    Cli.parseProgramArgs(split("plugins")) shouldEqual Some(Arguments(PLUGINS, Defaults.BaseUrl, None))
    Cli.parseProgramArgs(split("describe myconn")) shouldEqual Some(Arguments(DESCRIBE, Defaults.BaseUrl, Some("myconn")))
    Cli.parseProgramArgs(split("validate myconn")) shouldEqual Some(Arguments(VALIDATE, Defaults.BaseUrl, Some("myconn")))

    Cli.parseProgramArgs(split("restart myconn")) shouldEqual Some(Arguments(RESTART, Defaults.BaseUrl, Some("myconn")))
    Cli.parseProgramArgs(split("pause myconn")) shouldEqual Some(Arguments(PAUSE, Defaults.BaseUrl, Some("myconn")))
    Cli.parseProgramArgs(split("resume myconn")) shouldEqual Some(Arguments(RESUME, Defaults.BaseUrl, Some("myconn")))
  }

  test("Invalid program arguments are rejected") {
    Cli.parseProgramArgs(split("fakecmd")) shouldEqual None
    Cli.parseProgramArgs(split("rm")) shouldEqual None
    Cli.parseProgramArgs(split("create good -j nonsense")) shouldEqual None
  }
}

class ApiUnitTests extends FunSuite with Matchers with MockFactory {
  val URL = new java.net.URI("http://localhost")

  val acceptHeader = "Accept" -> "application/json"
  val contentTypeHeader = "Content-Type" -> "application/json"

  // creates a HttpClient mock that verifies input and produces output
  def verifyingHttpClient(endpoint: String, method: String, status: Int, resp: Option[String], verifyBody: String => Unit = a => {}) = {
    new HttpClient {
      def request(url: java.net.URI, method: String, hdrs: Seq[(String, String)], reqBody: Option[String]): Try[(Int, Option[String])] = {
        url shouldEqual URL.resolve(endpoint)
        method shouldEqual method
        hdrs should contain allOf(acceptHeader, contentTypeHeader)
        reqBody.foreach(verifyBody)
        Success((status, resp))
      }
    }
  }

  test("activeConnectorNames") {
    new RestKafkaConnectApi(URL,
      verifyingHttpClient("/connectors", "GET", 200, Some("""["a","b"]"""))
    ).activeConnectorNames shouldEqual Success("a" :: "b" :: Nil)
  }

  test("connectorInfo") {
    new RestKafkaConnectApi(URL,
      verifyingHttpClient("/connectors/some", "GET", 200, Some("""{"name":"nom","config":{"k":"v"},"tasks":[{"connector":"c0","task":5}]}"""))
    ).connectorInfo("some") shouldEqual Success(ConnectorInfo("nom", Map("k" -> "v"), List(Task("c0", 5))))
  }

  test("addConnector") {
    val verifyBody = (s: String) => {
      val jobj = s.parseJson.asJsObject
      jobj.fields("name").convertTo[String] shouldBe "some"
      jobj.fields("config").convertTo[Map[String, String]] shouldBe Map("prop" -> "val")
    }
    new RestKafkaConnectApi(URL,
      verifyingHttpClient("/connectors", "POST", 200, Some("""{"name":"nom","config":{"k":"v"},"tasks":[{"connector":"c0","task":5}]}"""), verifyBody)
    ).addConnector("some", Map("prop" -> "val")) shouldEqual Success(ConnectorInfo("nom", Map("k" -> "v"), List(Task("c0", 5))))
  }

  test("updateConnector") {
    val verifyBody = (s: String) => {
      val jobj = s.parseJson.convertTo[Map[String, String]] shouldBe Map("prop" -> "val")
    }
    new RestKafkaConnectApi(URL,
      verifyingHttpClient("/connectors/nome/config", "PUT", 200, Some("""{"name":"nom","config":{"k":"v"},"tasks":[{"connector":"c0","task":5}]}"""), verifyBody)
    ).updateConnector("nome", Map("prop" -> "val")) shouldEqual Success(ConnectorInfo("nom", Map("k" -> "v"), List(Task("c0", 5))))
  }

  test("delete") {
    new RestKafkaConnectApi(URL,
      verifyingHttpClient("/connectors/nome", "DELETE", 200, None)
    ).delete("nome") shouldEqual Success()
  }

  test("plugins") {
    val ret = new RestKafkaConnectApi(URL, verifyingHttpClient("/connector-plugins", "GET", 200, Some("""[{"class": "andrew"}]"""))
    ).connectorPlugins()

    ret shouldEqual Success(List(ConnectorPlugins("andrew")))
  }

  test("pauseConnector") {
    val mockedClient = mock[HttpClient]
    (mockedClient.request _).expects(URL.resolve("/connectors/nome/pause"), "PUT", Seq(acceptHeader, contentTypeHeader), None)
    (mockedClient.request _).expects(URL.resolve("/connectors/nome/status"), "GET",  Seq(acceptHeader, contentTypeHeader), None)
    new RestKafkaConnectApi(URL, mockedClient).connectorPause("nome")
  }

  test("resumeConnector") {
    val mockedClient = mock[HttpClient]
    (mockedClient.request _).expects(URL.resolve("/connectors/nome/resume"), "PUT", Seq(acceptHeader, contentTypeHeader), None)
    (mockedClient.request _).expects(URL.resolve("/connectors/nome/status"), "GET",  Seq(acceptHeader, contentTypeHeader), None)
    new RestKafkaConnectApi(URL, mockedClient).connectorResume("nome")
  }

  test("validate") {

    val config = """
      |{
      |    "name": "FileStreamSinkConnector",
      |    "error_count": 1,
      |    "groups": [
      |        "Common"
      |    ],
      |    "configs": [
      |         {
      |            "definition": {
      |                "name": "topics",
      |                "type": "LIST",
      |                "required": false,
      |                "default_value": "",
      |                "importance": "HIGH",
      |                "documentation": "",
      |                "group": "Common",
      |                "width": "LONG",
      |                "display_name": "Topics",
      |                "dependents": [],
      |                "order": 4
      |             },
      |            "value": {
      |                "name": "topics",
      |                "value": "test-topic",
      |                "recommended_values": [],
      |                "errors": [],
      |                "visible": true
      |            }
      |        }
      |   ]
      |}
    """.stripMargin

    import MyJsonProtocol._
    val jsonAst = config.parseJson
    val valid = jsonAst.convertTo[ConnectorPluginsValidate]

    val ret = new RestKafkaConnectApi(URL, verifyingHttpClient("/connector-plugins/myconn/config/validate", "PUT", 200, Some(config))
    ).connectorPluginsDescribe("myconn")

    ret.get.name shouldEqual valid.name
    ret.get.error_count shouldEqual valid.error_count
    ret.get.configs.head.definition.name shouldEqual valid.configs.head.definition.name
  }
}

class ExecuteCommandTest extends FunSuite with Matchers {
  test("properties") {
    val lines = List(
      "some.key=\\",
      "  val1,\\",
      "  val2"
    )

    val cmd = ExecuteCommand
    val props = cmd.propsToMap(lines)

    props.get("some.key") shouldEqual Some("val1,val2")
  }
}
