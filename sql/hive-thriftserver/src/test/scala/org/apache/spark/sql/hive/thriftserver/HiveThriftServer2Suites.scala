/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.hive.thriftserver

import java.io.{File, FilenameFilter}
import java.net.URL
import java.nio.charset.StandardCharsets
import java.sql.{Date, DriverManager, SQLException, Statement}
import java.util.{Locale, UUID}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.io.Source
import scala.util.{Random, Try}

import com.google.common.io.Files
import org.apache.hadoop.hive.conf.HiveConf.ConfVars
import org.apache.hive.jdbc.HiveDriver
import org.apache.hive.service.auth.PlainSaslHelper
import org.apache.hive.service.cli.{FetchOrientation, FetchType, GetInfoType, RowSet}
import org.apache.hive.service.cli.thrift.ThriftCLIServiceClient
import org.apache.thrift.protocol.TBinaryProtocol
import org.apache.thrift.transport.TSocket
import org.scalatest.BeforeAndAfterAll

import org.apache.spark.{SparkException, SparkFunSuite}
import org.apache.spark.internal.Logging
import org.apache.spark.sql.hive.HiveUtils
import org.apache.spark.sql.hive.test.HiveTestJars
import org.apache.spark.sql.internal.StaticSQLConf.HIVE_THRIFT_SERVER_SINGLESESSION
import org.apache.spark.sql.test.ProcessTestUtils.ProcessOutputCapturer
import org.apache.spark.util.{ThreadUtils, Utils}

object TestData {
  def getTestDataFilePath(name: String): URL = {
    Thread.currentThread().getContextClassLoader.getResource(s"data/files/$name")
  }

  val smallKv = getTestDataFilePath("small_kv.txt")
  val smallKvWithNull = getTestDataFilePath("small_kv_with_null.txt")
}

class HiveThriftBinaryServerSuite extends HiveThriftJdbcTest {
  override def mode: ServerMode.Value = ServerMode.binary

  private def withCLIServiceClient(f: ThriftCLIServiceClient => Unit): Unit = {
    // Transport creation logic below mimics HiveConnection.createBinaryTransport
    val rawTransport = new TSocket("localhost", serverPort)
    val user = System.getProperty("user.name")
    val transport = PlainSaslHelper.getPlainTransport(user, "anonymous", rawTransport)
    val protocol = new TBinaryProtocol(transport)
    val client = new ThriftCLIServiceClient(new ThriftserverShimUtils.Client(protocol))

    transport.open()
    try f(client) finally transport.close()
  }

  test("GetInfo Thrift API") {
    withCLIServiceClient { client =>
      val user = System.getProperty("user.name")
      val sessionHandle = client.openSession(user, "")

      assertResult("Spark SQL", "Wrong GetInfo(CLI_DBMS_NAME) result") {
        client.getInfo(sessionHandle, GetInfoType.CLI_DBMS_NAME).getStringValue
      }

      assertResult("Spark SQL", "Wrong GetInfo(CLI_SERVER_NAME) result") {
        client.getInfo(sessionHandle, GetInfoType.CLI_SERVER_NAME).getStringValue
      }

      assertResult(true, "Spark version shouldn't be \"Unknown\"") {
        val version = client.getInfo(sessionHandle, GetInfoType.CLI_DBMS_VER).getStringValue
        logInfo(s"Spark version: $version")
        version != "Unknown"
      }
    }
  }

  test("SPARK-16563 ThriftCLIService FetchResults repeat fetching result") {
    withCLIServiceClient { client =>
      val user = System.getProperty("user.name")
      val sessionHandle = client.openSession(user, "")

      withJdbcStatement("test_16563") { statement =>
        val queries = Seq(
          "CREATE TABLE test_16563(key INT, val STRING) USING hive",
          s"LOAD DATA LOCAL INPATH '${TestData.smallKv}' OVERWRITE INTO TABLE test_16563")

        queries.foreach(statement.execute)
        val confOverlay = new java.util.HashMap[java.lang.String, java.lang.String]
        val operationHandle = client.executeStatement(
          sessionHandle,
          "SELECT * FROM test_16563",
          confOverlay)

        // Fetch result first time
        assertResult(5, "Fetching result first time from next row") {

          val rows_next = client.fetchResults(
            operationHandle,
            FetchOrientation.FETCH_NEXT,
            1000,
            FetchType.QUERY_OUTPUT)

          rows_next.numRows()
        }

        // Fetch result second time from first row
        assertResult(5, "Repeat fetching result from first row") {

          val rows_first = client.fetchResults(
            operationHandle,
            FetchOrientation.FETCH_FIRST,
            1000,
            FetchType.QUERY_OUTPUT)

          rows_first.numRows()
        }
      }
    }
  }

  test("Support beeline --hiveconf and --hivevar") {
    withJdbcStatement() { statement =>
      executeTest(hiveConfList)
      executeTest(hiveVarList)
      def executeTest(hiveList: String): Unit = {
        hiveList.split(";").foreach{ m =>
          val kv = m.split("=")
          val k = kv(0)
          val v = kv(1)
          val modValue = s"${v}_MOD_VALUE"
          // select '${a}'; ---> avalue
          val resultSet = statement.executeQuery(s"select '$${$k}'")
          resultSet.next()
          assert(resultSet.getString(1) === v)
          statement.executeQuery(s"set $k=$modValue")
          val modResultSet = statement.executeQuery(s"select '$${$k}'")
          modResultSet.next()
          assert(modResultSet.getString(1) === s"$modValue")
        }
      }
    }
  }

  test("JDBC query execution") {
    withJdbcStatement("test") { statement =>
      val queries = Seq(
        "SET spark.sql.shuffle.partitions=3",
        "CREATE TABLE test(key INT, val STRING) USING hive",
        s"LOAD DATA LOCAL INPATH '${TestData.smallKv}' OVERWRITE INTO TABLE test",
        "CACHE TABLE test")

      queries.foreach(statement.execute)

      assertResult(5, "Row count mismatch") {
        val resultSet = statement.executeQuery("SELECT COUNT(*) FROM test")
        resultSet.next()
        resultSet.getInt(1)
      }
    }
  }

  test("Checks Hive version") {
    withJdbcStatement() { statement =>
      val resultSet = statement.executeQuery("SET spark.sql.hive.version")
      resultSet.next()
      assert(resultSet.getString(1) === "spark.sql.hive.version")
      assert(resultSet.getString(2) === HiveUtils.builtinHiveVersion)
    }
  }

  test("SPARK-3004 regression: result set containing NULL") {
    withJdbcStatement("test_null") { statement =>
      val queries = Seq(
        "CREATE TABLE test_null(key INT, val STRING) USING hive",
        s"LOAD DATA LOCAL INPATH '${TestData.smallKvWithNull}' OVERWRITE INTO TABLE test_null")

      queries.foreach(statement.execute)

      val resultSet = statement.executeQuery("SELECT * FROM test_null WHERE key IS NULL")

      (0 until 5).foreach { _ =>
        resultSet.next()
        assert(resultSet.getInt(1) === 0)
        assert(resultSet.wasNull())
      }

      assert(!resultSet.next())
    }
  }

  test("SPARK-4292 regression: result set iterator issue") {
    withJdbcStatement("test_4292") { statement =>
      val queries = Seq(
        "CREATE TABLE test_4292(key INT, val STRING) USING hive",
        s"LOAD DATA LOCAL INPATH '${TestData.smallKv}' OVERWRITE INTO TABLE test_4292")

      queries.foreach(statement.execute)

      val resultSet = statement.executeQuery("SELECT key FROM test_4292")

      Seq(238, 86, 311, 27, 165).foreach { key =>
        resultSet.next()
        assert(resultSet.getInt(1) === key)
      }
    }
  }

  test("SPARK-4309 regression: Date type support") {
    withJdbcStatement("test_date") { statement =>
      val queries = Seq(
        "CREATE TABLE test_date(key INT, value STRING) USING hive",
        s"LOAD DATA LOCAL INPATH '${TestData.smallKv}' OVERWRITE INTO TABLE test_date")

      queries.foreach(statement.execute)

      assertResult(Date.valueOf("2011-01-01")) {
        val resultSet = statement.executeQuery(
          "SELECT CAST('2011-01-01' as date) FROM test_date LIMIT 1")
        resultSet.next()
        resultSet.getDate(1)
      }
    }
  }

  test("SPARK-4407 regression: Complex type support") {
    withJdbcStatement("test_map") { statement =>
      val queries = Seq(
        "CREATE TABLE test_map(key INT, value STRING) USING hive",
        s"LOAD DATA LOCAL INPATH '${TestData.smallKv}' OVERWRITE INTO TABLE test_map")

      queries.foreach(statement.execute)

      assertResult("""{238:"val_238"}""") {
        val resultSet = statement.executeQuery("SELECT MAP(key, value) FROM test_map LIMIT 1")
        resultSet.next()
        resultSet.getString(1)
      }

      assertResult("""["238","val_238"]""") {
        val resultSet = statement.executeQuery(
          "SELECT ARRAY(CAST(key AS STRING), value) FROM test_map LIMIT 1")
        resultSet.next()
        resultSet.getString(1)
      }
    }
  }

  test("SPARK-12143 regression: Binary type support") {
    withJdbcStatement("test_binary") { statement =>
      val queries = Seq(
        "CREATE TABLE test_binary(key INT, value STRING) USING hive",
        s"LOAD DATA LOCAL INPATH '${TestData.smallKv}' OVERWRITE INTO TABLE test_binary")

      queries.foreach(statement.execute)

      val expected: Array[Byte] = "val_238".getBytes
      assertResult(expected) {
        val resultSet = statement.executeQuery(
          "SELECT CAST(value as BINARY) FROM test_binary LIMIT 1")
        resultSet.next()
        resultSet.getObject(1)
      }
    }
  }

  test("test multiple session") {
    import org.apache.spark.sql.internal.SQLConf
    var defaultV1: String = null
    var defaultV2: String = null
    var data: ArrayBuffer[Int] = null

    withMultipleConnectionJdbcStatement("test_map", "db1.test_map2")(
      // create table
      { statement =>

        val queries = Seq(
            "CREATE TABLE test_map(key INT, value STRING) USING hive",
            s"LOAD DATA LOCAL INPATH '${TestData.smallKv}' OVERWRITE INTO TABLE test_map",
            "CACHE TABLE test_table AS SELECT key FROM test_map ORDER BY key DESC",
            "CREATE DATABASE db1")

        queries.foreach(statement.execute)

        val plan = statement.executeQuery("explain select * from test_table")
        plan.next()
        plan.next()
        assert(plan.getString(1).contains("Scan In-memory table `test_table`"))

        val rs1 = statement.executeQuery("SELECT key FROM test_table ORDER BY KEY DESC")
        val buf1 = new collection.mutable.ArrayBuffer[Int]()
        while (rs1.next()) {
          buf1 += rs1.getInt(1)
        }
        rs1.close()

        val rs2 = statement.executeQuery("SELECT key FROM test_map ORDER BY KEY DESC")
        val buf2 = new collection.mutable.ArrayBuffer[Int]()
        while (rs2.next()) {
          buf2 += rs2.getInt(1)
        }
        rs2.close()

        assert(buf1 === buf2)

        data = buf1
      },

      // first session, we get the default value of the session status
      { statement =>

        val rs1 = statement.executeQuery(s"SET ${SQLConf.SHUFFLE_PARTITIONS.key}")
        rs1.next()
        defaultV1 = rs1.getString(1)
        assert(defaultV1 != "200")
        rs1.close()

        val rs2 = statement.executeQuery("SET hive.cli.print.header")
        rs2.next()

        defaultV2 = rs2.getString(1)
        assert(defaultV1 != "true")
        rs2.close()
      },

      // second session, we update the session status
      { statement =>

        val queries = Seq(
            s"SET ${SQLConf.SHUFFLE_PARTITIONS.key}=291",
            "SET hive.cli.print.header=true"
            )

        queries.map(statement.execute)
        val rs1 = statement.executeQuery(s"SET ${SQLConf.SHUFFLE_PARTITIONS.key}")
        rs1.next()
        assert("spark.sql.shuffle.partitions" === rs1.getString(1))
        assert("291" === rs1.getString(2))
        rs1.close()

        val rs2 = statement.executeQuery("SET hive.cli.print.header")
        rs2.next()
        assert("hive.cli.print.header" === rs2.getString(1))
        assert("true" === rs2.getString(2))
        rs2.close()
      },

      // third session, we get the latest session status, supposed to be the
      // default value
      { statement =>

        val rs1 = statement.executeQuery(s"SET ${SQLConf.SHUFFLE_PARTITIONS.key}")
        rs1.next()
        assert(defaultV1 === rs1.getString(1))
        rs1.close()

        val rs2 = statement.executeQuery("SET hive.cli.print.header")
        rs2.next()
        assert(defaultV2 === rs2.getString(1))
        rs2.close()
      },

      // try to access the cached data in another session
      { statement =>

        // Cached temporary table can't be accessed by other sessions
        intercept[SQLException] {
          statement.executeQuery("SELECT key FROM test_table ORDER BY KEY DESC")
        }

        val plan = statement.executeQuery("explain select key from test_map ORDER BY key DESC")
        plan.next()
        plan.next()
        assert(plan.getString(1).contains("Scan In-memory table `test_table`"))

        val rs = statement.executeQuery("SELECT key FROM test_map ORDER BY KEY DESC")
        val buf = new collection.mutable.ArrayBuffer[Int]()
        while (rs.next()) {
          buf += rs.getInt(1)
        }
        rs.close()
        assert(buf === data)
      },

      // switch another database
      { statement =>
        statement.execute("USE db1")

        // there is no test_map table in db1
        intercept[SQLException] {
          statement.executeQuery("SELECT key FROM test_map ORDER BY KEY DESC")
        }

        statement.execute("CREATE TABLE test_map2(key INT, value STRING)")
      },

      // access default database
      { statement =>

        // current database should still be `default`
        intercept[SQLException] {
          statement.executeQuery("SELECT key FROM test_map2")
        }

        statement.execute("USE db1")
        // access test_map2
        statement.executeQuery("SELECT key from test_map2")
      }
    )
  }

  // This test often hangs and then times out, leaving the hanging processes.
  // Let's ignore it and improve the test.
  ignore("test jdbc cancel") {
    withJdbcStatement("test_map") { statement =>
      val queries = Seq(
        "CREATE TABLE test_map(key INT, value STRING)",
        s"LOAD DATA LOCAL INPATH '${TestData.smallKv}' OVERWRITE INTO TABLE test_map")

      queries.foreach(statement.execute)
      implicit val ec = ExecutionContext.fromExecutorService(
        ThreadUtils.newDaemonSingleThreadExecutor("test-jdbc-cancel"))
      try {
        // Start a very-long-running query that will take hours to finish, then cancel it in order
        // to demonstrate that cancellation works.
        val f = Future {
          statement.executeQuery(
            "SELECT COUNT(*) FROM test_map " +
            List.fill(10)("join test_map").mkString(" "))
        }
        // Note that this is slightly race-prone: if the cancel is issued before the statement
        // begins executing then we'll fail with a timeout. As a result, this fixed delay is set
        // slightly more conservatively than may be strictly necessary.
        Thread.sleep(1000)
        statement.cancel()
        val e = intercept[SparkException] {
          ThreadUtils.awaitResult(f, 3.minute)
        }.getCause
        assert(e.isInstanceOf[SQLException])
        assert(e.getMessage.contains("cancelled"))

        // Cancellation is a no-op if spark.sql.hive.thriftServer.async=false
        statement.executeQuery("SET spark.sql.hive.thriftServer.async=false")
        try {
          val sf = Future {
            statement.executeQuery(
              "SELECT COUNT(*) FROM test_map " +
                List.fill(4)("join test_map").mkString(" ")
            )
          }
          // Similarly, this is also slightly race-prone on fast machines where the query above
          // might race and complete before we issue the cancel.
          Thread.sleep(1000)
          statement.cancel()
          val rs1 = ThreadUtils.awaitResult(sf, 3.minute)
          rs1.next()
          assert(rs1.getInt(1) === math.pow(5, 5))
          rs1.close()

          val rs2 = statement.executeQuery("SELECT COUNT(*) FROM test_map")
          rs2.next()
          assert(rs2.getInt(1) === 5)
          rs2.close()
        } finally {
          statement.executeQuery("SET spark.sql.hive.thriftServer.async=true")
        }
      } finally {
        ec.shutdownNow()
      }
    }
  }

  test("test add jar") {
    withMultipleConnectionJdbcStatement("smallKV", "addJar")(
      {
        statement =>
          val jarFile = HiveTestJars.getHiveHcatalogCoreJar().getCanonicalPath

          statement.executeQuery(s"ADD JAR $jarFile")
      },

      {
        statement =>
          val queries = Seq(
            "CREATE TABLE smallKV(key INT, val STRING) USING hive",
            s"LOAD DATA LOCAL INPATH '${TestData.smallKv}' OVERWRITE INTO TABLE smallKV",
            """CREATE TABLE addJar(key string)
              |ROW FORMAT SERDE 'org.apache.hive.hcatalog.data.JsonSerDe'
            """.stripMargin)

          queries.foreach(statement.execute)

          statement.executeQuery(
            """
              |INSERT INTO TABLE addJar SELECT 'k1' as key FROM smallKV limit 1
            """.stripMargin)

          val actualResult =
            statement.executeQuery("SELECT key FROM addJar")
          val actualResultBuffer = new collection.mutable.ArrayBuffer[String]()
          while (actualResult.next()) {
            actualResultBuffer += actualResult.getString(1)
          }
          actualResult.close()

          val expectedResult =
            statement.executeQuery("SELECT 'k1'")
          val expectedResultBuffer = new collection.mutable.ArrayBuffer[String]()
          while (expectedResult.next()) {
            expectedResultBuffer += expectedResult.getString(1)
          }
          expectedResult.close()

          assert(expectedResultBuffer === actualResultBuffer)
      }
    )
  }

  test("Checks Hive version via SET -v") {
    withJdbcStatement() { statement =>
      val resultSet = statement.executeQuery("SET -v")

      val conf = mutable.Map.empty[String, String]
      while (resultSet.next()) {
        conf += resultSet.getString(1) -> resultSet.getString(2)
      }

      if (HiveUtils.isHive23) {
        assert(conf.get(HiveUtils.FAKE_HIVE_VERSION.key) === Some("2.3.6"))
      } else {
        assert(conf.get(HiveUtils.FAKE_HIVE_VERSION.key) === Some("1.2.1"))
      }
    }
  }

  test("Checks Hive version via SET") {
    withJdbcStatement() { statement =>
      val resultSet = statement.executeQuery("SET")

      val conf = mutable.Map.empty[String, String]
      while (resultSet.next()) {
        conf += resultSet.getString(1) -> resultSet.getString(2)
      }

      if (HiveUtils.isHive23) {
        assert(conf.get(HiveUtils.FAKE_HIVE_VERSION.key) === Some("2.3.6"))
      } else {
        assert(conf.get(HiveUtils.FAKE_HIVE_VERSION.key) === Some("1.2.1"))
      }
    }
  }

  test("SPARK-11595 ADD JAR with input path having URL scheme") {
    withJdbcStatement("test_udtf") { statement =>
      try {
        val jarPath = "../hive/src/test/resources/TestUDTF.jar"
        val jarURL = s"file://${System.getProperty("user.dir")}/$jarPath"

        Seq(
          s"ADD JAR $jarURL",
          s"""CREATE TEMPORARY FUNCTION udtf_count2
             |AS 'org.apache.spark.sql.hive.execution.GenericUDTFCount2'
           """.stripMargin
        ).foreach(statement.execute)

        val rs1 = statement.executeQuery("DESCRIBE FUNCTION udtf_count2")

        assert(rs1.next())
        assert(rs1.getString(1) === "Function: udtf_count2")

        assert(rs1.next())
        assertResult("Class: org.apache.spark.sql.hive.execution.GenericUDTFCount2") {
          rs1.getString(1)
        }

        assert(rs1.next())
        assert(rs1.getString(1) === "Usage: N/A.")

        val dataPath = "../hive/src/test/resources/data/files/kv1.txt"

        Seq(
          "CREATE TABLE test_udtf(key INT, value STRING) USING hive",
          s"LOAD DATA LOCAL INPATH '$dataPath' OVERWRITE INTO TABLE test_udtf"
        ).foreach(statement.execute)

        val rs2 = statement.executeQuery(
          "SELECT key, cc FROM test_udtf LATERAL VIEW udtf_count2(value) dd AS cc")

        assert(rs2.next())
        assert(rs2.getInt(1) === 97)
        assert(rs2.getInt(2) === 500)

        assert(rs2.next())
        assert(rs2.getInt(1) === 97)
        assert(rs2.getInt(2) === 500)
      } finally {
        statement.executeQuery("DROP TEMPORARY FUNCTION udtf_count2")
      }
    }
  }

  test("SPARK-11043 check operation log root directory") {
    val expectedLine =
      "Operation log root directory is created: " + operationLogPath.getAbsoluteFile
    val bufferSrc = Source.fromFile(logPath)
    Utils.tryWithSafeFinally {
      assert(bufferSrc.getLines().exists(_.contains(expectedLine)))
    } {
      bufferSrc.close()
    }
  }

  test("SPARK-23547 Cleanup the .pipeout file when the Hive Session closed") {
    def pipeoutFileList(sessionID: UUID): Array[File] = {
      lScratchDir.listFiles(new FilenameFilter {
        override def accept(dir: File, name: String): Boolean = {
          name.startsWith(sessionID.toString) && name.endsWith(".pipeout")
        }
      })
    }

    withCLIServiceClient { client =>
      val user = System.getProperty("user.name")
      val sessionHandle = client.openSession(user, "")
      val sessionID = sessionHandle.getSessionId

      if (HiveUtils.isHive23) {
        assert(pipeoutFileList(sessionID).length == 2)
      } else {
        assert(pipeoutFileList(sessionID).length == 1)
      }

      client.closeSession(sessionHandle)

      assert(pipeoutFileList(sessionID).length == 0)
    }
  }

  test("SPARK-24829 Checks cast as float") {
    withJdbcStatement() { statement =>
      val resultSet = statement.executeQuery("SELECT CAST('4.56' AS FLOAT)")
      resultSet.next()
      assert(resultSet.getString(1) === "4.56")
    }
  }

  test("SPARK-28463: Thriftserver throws BigDecimal incompatible with HiveDecimal") {
    withJdbcStatement() { statement =>
      val rs = statement.executeQuery("SELECT CAST(1 AS decimal(38, 18))")
      assert(rs.next())
      assert(rs.getBigDecimal(1) === new java.math.BigDecimal("1.000000000000000000"))
    }
  }

  test("Support interval type") {
    withJdbcStatement() { statement =>
      val rs = statement.executeQuery("SELECT interval 3 months 1 hours")
      assert(rs.next())
      assert(rs.getString(1) === "3 months 1 hours")
    }
    // Invalid interval value
    withJdbcStatement() { statement =>
      val e = intercept[SQLException] {
        statement.executeQuery("SELECT interval 3 months 1 hou")
      }
      assert(e.getMessage.contains("org.apache.spark.sql.catalyst.parser.ParseException"))
    }
  }

  test("ThriftCLIService FetchResults FETCH_FIRST, FETCH_NEXT, FETCH_PRIOR") {
    def checkResult(rows: RowSet, start: Long, end: Long): Unit = {
      assert(rows.getStartOffset() == start)
      assert(rows.numRows() == end - start)
      rows.iterator.asScala.zip((start until end).iterator).foreach { case (row, v) =>
        assert(row(0).asInstanceOf[Long] === v)
      }
    }

    withCLIServiceClient { client =>
      val user = System.getProperty("user.name")
      val sessionHandle = client.openSession(user, "")

      val confOverlay = new java.util.HashMap[java.lang.String, java.lang.String]
      val operationHandle = client.executeStatement(
        sessionHandle,
        "SELECT * FROM range(10)",
        confOverlay) // 10 rows result with sequence 0, 1, 2, ..., 9
      var rows: RowSet = null

      // Fetch 5 rows with FETCH_NEXT
      rows = client.fetchResults(
        operationHandle, FetchOrientation.FETCH_NEXT, 5, FetchType.QUERY_OUTPUT)
      checkResult(rows, 0, 5) // fetched [0, 5)

      // Fetch another 2 rows with FETCH_NEXT
      rows = client.fetchResults(
        operationHandle, FetchOrientation.FETCH_NEXT, 2, FetchType.QUERY_OUTPUT)
      checkResult(rows, 5, 7) // fetched [5, 7)

      // FETCH_PRIOR 3 rows
      rows = client.fetchResults(
        operationHandle, FetchOrientation.FETCH_PRIOR, 3, FetchType.QUERY_OUTPUT)
      checkResult(rows, 2, 5) // fetched [2, 5)

      // FETCH_PRIOR again will scroll back to 0, and then the returned result
      // may overlap the results of previous FETCH_PRIOR
      rows = client.fetchResults(
        operationHandle, FetchOrientation.FETCH_PRIOR, 3, FetchType.QUERY_OUTPUT)
      checkResult(rows, 0, 3) // fetched [0, 3)

      // FETCH_PRIOR again will stay at 0
      rows = client.fetchResults(
        operationHandle, FetchOrientation.FETCH_PRIOR, 4, FetchType.QUERY_OUTPUT)
      checkResult(rows, 0, 4) // fetched [0, 4)

      // FETCH_NEXT will continue moving forward from offset 4
      rows = client.fetchResults(
        operationHandle, FetchOrientation.FETCH_NEXT, 10, FetchType.QUERY_OUTPUT)
      checkResult(rows, 4, 10) // fetched [4, 10) until the end of results

      // FETCH_NEXT is at end of results
      rows = client.fetchResults(
        operationHandle, FetchOrientation.FETCH_NEXT, 5, FetchType.QUERY_OUTPUT)
      checkResult(rows, 10, 10) // fetched empty [10, 10) (at end of results)

      // FETCH_NEXT is at end of results again
      rows = client.fetchResults(
        operationHandle, FetchOrientation.FETCH_NEXT, 2, FetchType.QUERY_OUTPUT)
      checkResult(rows, 10, 10) // fetched empty [10, 10) (at end of results)

      // FETCH_PRIOR 1 rows yet again
      rows = client.fetchResults(
        operationHandle, FetchOrientation.FETCH_PRIOR, 1, FetchType.QUERY_OUTPUT)
      checkResult(rows, 9, 10) // fetched [9, 10)

      // FETCH_NEXT will return 0 yet again
      rows = client.fetchResults(
        operationHandle, FetchOrientation.FETCH_NEXT, 5, FetchType.QUERY_OUTPUT)
      checkResult(rows, 10, 10) // fetched empty [10, 10) (at end of results)

      // FETCH_FIRST results from first row
      rows = client.fetchResults(
        operationHandle, FetchOrientation.FETCH_FIRST, 3, FetchType.QUERY_OUTPUT)
      checkResult(rows, 0, 3) // fetch [0, 3)

      // Fetch till the end rows with FETCH_NEXT"
      rows = client.fetchResults(
        operationHandle, FetchOrientation.FETCH_NEXT, 1000, FetchType.QUERY_OUTPUT)
      checkResult(rows, 3, 10) // fetched [3, 10)

      client.closeOperation(operationHandle)
      client.closeSession(sessionHandle)
    }
  }
}

class SingleSessionSuite extends HiveThriftJdbcTest {
  override def mode: ServerMode.Value = ServerMode.binary

  override protected def extraConf: Seq[String] =
    s"--conf ${HIVE_THRIFT_SERVER_SINGLESESSION.key}=true" :: Nil

  test("share the temporary functions across JDBC connections") {
    withMultipleConnectionJdbcStatement()(
      { statement =>
        val jarPath = "../hive/src/test/resources/TestUDTF.jar"
        val jarURL = s"file://${System.getProperty("user.dir")}/$jarPath"

        // Configurations and temporary functions added in this session should be visible to all
        // the other sessions.
        Seq(
          "SET foo=bar",
          s"ADD JAR $jarURL",
          s"""CREATE TEMPORARY FUNCTION udtf_count2
              |AS 'org.apache.spark.sql.hive.execution.GenericUDTFCount2'
           """.stripMargin
        ).foreach(statement.execute)
      },

      { statement =>
        try {
          val rs1 = statement.executeQuery("SET foo")

          assert(rs1.next())
          assert(rs1.getString(1) === "foo")
          assert(rs1.getString(2) === "bar")

          val rs2 = statement.executeQuery("DESCRIBE FUNCTION udtf_count2")

          assert(rs2.next())
          assert(rs2.getString(1) === "Function: udtf_count2")

          assert(rs2.next())
          assertResult("Class: org.apache.spark.sql.hive.execution.GenericUDTFCount2") {
            rs2.getString(1)
          }

          assert(rs2.next())
          assert(rs2.getString(1) === "Usage: N/A.")
        } finally {
          statement.executeQuery("DROP TEMPORARY FUNCTION udtf_count2")
        }
      }
    )
  }

  test("unable to changing spark.sql.hive.thriftServer.singleSession using JDBC connections") {
    withJdbcStatement() { statement =>
      // JDBC connections are not able to set the conf spark.sql.hive.thriftServer.singleSession
      val e = intercept[SQLException] {
        statement.executeQuery("SET spark.sql.hive.thriftServer.singleSession=false")
      }.getMessage
      assert(e.contains(
        "Cannot modify the value of a static config: spark.sql.hive.thriftServer.singleSession"))
    }
  }

  test("share the current database and temporary tables across JDBC connections") {
    withMultipleConnectionJdbcStatement()(
      { statement =>
        statement.execute("CREATE DATABASE IF NOT EXISTS db1")
      },

      { statement =>
        val rs1 = statement.executeQuery("SELECT current_database()")
        assert(rs1.next())
        assert(rs1.getString(1) === "default")

        statement.execute("USE db1")

        val rs2 = statement.executeQuery("SELECT current_database()")
        assert(rs2.next())
        assert(rs2.getString(1) === "db1")

        statement.execute("CREATE TEMP VIEW tempView AS SELECT 123")
      },

      { statement =>
        // the current database is set to db1 by another JDBC connection.
        val rs1 = statement.executeQuery("SELECT current_database()")
        assert(rs1.next())
        assert(rs1.getString(1) === "db1")

        val rs2 = statement.executeQuery("SELECT * from tempView")
        assert(rs2.next())
        assert(rs2.getString(1) === "123")

        statement.execute("USE default")
        statement.execute("DROP VIEW tempView")
        statement.execute("DROP DATABASE db1 CASCADE")
      }
    )
  }
}

class HiveThriftHttpServerSuite extends HiveThriftJdbcTest {
  override def mode: ServerMode.Value = ServerMode.http

  test("JDBC query execution") {
    withJdbcStatement("test") { statement =>
      val queries = Seq(
        "SET spark.sql.shuffle.partitions=3",
        "CREATE TABLE test(key INT, val STRING) USING hive",
        s"LOAD DATA LOCAL INPATH '${TestData.smallKv}' OVERWRITE INTO TABLE test",
        "CACHE TABLE test")

      queries.foreach(statement.execute)

      assertResult(5, "Row count mismatch") {
        val resultSet = statement.executeQuery("SELECT COUNT(*) FROM test")
        resultSet.next()
        resultSet.getInt(1)
      }
    }
  }

  test("Checks Hive version") {
    withJdbcStatement() { statement =>
      val resultSet = statement.executeQuery("SET spark.sql.hive.version")
      resultSet.next()
      assert(resultSet.getString(1) === "spark.sql.hive.version")
      assert(resultSet.getString(2) === HiveUtils.builtinHiveVersion)
    }
  }

  test("SPARK-24829 Checks cast as float") {
    withJdbcStatement() { statement =>
      val resultSet = statement.executeQuery("SELECT CAST('4.56' AS FLOAT)")
      resultSet.next()
      assert(resultSet.getString(1) === "4.56")
    }
  }
}

object ServerMode extends Enumeration {
  val binary, http = Value
}

abstract class HiveThriftJdbcTest extends HiveThriftServer2Test {
  Utils.classForName(classOf[HiveDriver].getCanonicalName)

  private def jdbcUri = if (mode == ServerMode.http) {
    s"""jdbc:hive2://localhost:$serverPort/
       |default?
       |hive.server2.transport.mode=http;
       |hive.server2.thrift.http.path=cliservice;
       |${hiveConfList}#${hiveVarList}
     """.stripMargin.split("\n").mkString.trim
  } else {
    s"jdbc:hive2://localhost:$serverPort/?${hiveConfList}#${hiveVarList}"
  }

  def withMultipleConnectionJdbcStatement(tableNames: String*)(fs: (Statement => Unit)*): Unit = {
    val user = System.getProperty("user.name")
    val connections = fs.map { _ => DriverManager.getConnection(jdbcUri, user, "") }
    val statements = connections.map(_.createStatement())

    try {
      statements.zip(fs).foreach { case (s, f) => f(s) }
    } finally {
      tableNames.foreach { name =>
        // TODO: Need a better way to drop the view.
        if (name.toUpperCase(Locale.ROOT).startsWith("VIEW")) {
          statements(0).execute(s"DROP VIEW IF EXISTS $name")
        } else {
          statements(0).execute(s"DROP TABLE IF EXISTS $name")
        }
      }
      statements.foreach(_.close())
      connections.foreach(_.close())
    }
  }

  def withDatabase(dbNames: String*)(fs: (Statement => Unit)*): Unit = {
    val user = System.getProperty("user.name")
    val connections = fs.map { _ => DriverManager.getConnection(jdbcUri, user, "") }
    val statements = connections.map(_.createStatement())

    try {
      statements.zip(fs).foreach { case (s, f) => f(s) }
    } finally {
      dbNames.foreach { name =>
        statements(0).execute(s"DROP DATABASE IF EXISTS $name")
      }
      statements.foreach(_.close())
      connections.foreach(_.close())
    }
  }

  def withJdbcStatement(tableNames: String*)(f: Statement => Unit): Unit = {
    withMultipleConnectionJdbcStatement(tableNames: _*)(f)
  }
}

abstract class HiveThriftServer2Test extends SparkFunSuite with BeforeAndAfterAll with Logging {
  def mode: ServerMode.Value

  private val CLASS_NAME = HiveThriftServer2.getClass.getCanonicalName.stripSuffix("$")
  private val LOG_FILE_MARK = s"starting $CLASS_NAME, logging to "

  protected val startScript = "../../sbin/start-thriftserver.sh".split("/").mkString(File.separator)
  protected val stopScript = "../../sbin/stop-thriftserver.sh".split("/").mkString(File.separator)

  private var listeningPort: Int = _
  protected def serverPort: Int = listeningPort

  protected val hiveConfList = "a=avalue;b=bvalue"
  protected val hiveVarList = "c=cvalue;d=dvalue"
  protected def user = System.getProperty("user.name")

  protected var warehousePath: File = _
  protected var metastorePath: File = _
  protected def metastoreJdbcUri = s"jdbc:derby:;databaseName=$metastorePath;create=true"

  private val pidDir: File = Utils.createTempDir(namePrefix = "thriftserver-pid")
  protected var logPath: File = _
  protected var operationLogPath: File = _
  protected var lScratchDir: File = _
  private var logTailingProcess: Process = _
  private var diagnosisBuffer: ArrayBuffer[String] = ArrayBuffer.empty[String]

  protected def extraConf: Seq[String] = Nil

  protected def serverStartCommand(port: Int) = {
    val portConf = if (mode == ServerMode.binary) {
      ConfVars.HIVE_SERVER2_THRIFT_PORT
    } else {
      ConfVars.HIVE_SERVER2_THRIFT_HTTP_PORT
    }

    val driverClassPath = {
      // Writes a temporary log4j.properties and prepend it to driver classpath, so that it
      // overrides all other potential log4j configurations contained in other dependency jar files.
      val tempLog4jConf = Utils.createTempDir().getCanonicalPath

      Files.write(
        """log4j.rootCategory=DEBUG, console
          |log4j.appender.console=org.apache.log4j.ConsoleAppender
          |log4j.appender.console.target=System.err
          |log4j.appender.console.layout=org.apache.log4j.PatternLayout
          |log4j.appender.console.layout.ConversionPattern=%d{yy/MM/dd HH:mm:ss} %p %c{1}: %m%n
        """.stripMargin,
        new File(s"$tempLog4jConf/log4j.properties"),
        StandardCharsets.UTF_8)

      tempLog4jConf
    }

    s"""$startScript
       |  --master local
       |  --hiveconf ${ConfVars.METASTORECONNECTURLKEY}=$metastoreJdbcUri
       |  --hiveconf ${ConfVars.METASTOREWAREHOUSE}=$warehousePath
       |  --hiveconf ${ConfVars.HIVE_SERVER2_THRIFT_BIND_HOST}=localhost
       |  --hiveconf ${ConfVars.HIVE_SERVER2_TRANSPORT_MODE}=$mode
       |  --hiveconf ${ConfVars.HIVE_SERVER2_LOGGING_OPERATION_LOG_LOCATION}=$operationLogPath
       |  --hiveconf ${ConfVars.LOCALSCRATCHDIR}=$lScratchDir
       |  --hiveconf $portConf=$port
       |  --driver-class-path $driverClassPath
       |  --driver-java-options -Dlog4j.debug
       |  --conf spark.ui.enabled=false
       |  ${extraConf.mkString("\n")}
     """.stripMargin.split("\\s+").toSeq
  }

  /**
   * String to scan for when looking for the thrift binary endpoint running.
   * This can change across Hive versions.
   */
  val THRIFT_BINARY_SERVICE_LIVE = "Starting ThriftBinaryCLIService on port"

  /**
   * String to scan for when looking for the thrift HTTP endpoint running.
   * This can change across Hive versions.
   */
  val THRIFT_HTTP_SERVICE_LIVE = "Started ThriftHttpCLIService in http"

  val SERVER_STARTUP_TIMEOUT = 3.minutes

  private def startThriftServer(port: Int, attempt: Int) = {
    warehousePath = Utils.createTempDir()
    warehousePath.delete()
    metastorePath = Utils.createTempDir()
    metastorePath.delete()
    operationLogPath = Utils.createTempDir()
    operationLogPath.delete()
    lScratchDir = Utils.createTempDir()
    lScratchDir.delete()
    logPath = null
    logTailingProcess = null

    val command = serverStartCommand(port)

    diagnosisBuffer ++=
      s"""
         |### Attempt $attempt ###
         |HiveThriftServer2 command line: $command
         |Listening port: $port
         |System user: $user
       """.stripMargin.split("\n")

    logInfo(s"Trying to start HiveThriftServer2: port=$port, mode=$mode, attempt=$attempt")

    logPath = {
      val lines = Utils.executeAndGetOutput(
        command = command,
        extraEnvironment = Map(
          // Disables SPARK_TESTING to exclude log4j.properties in test directories.
          "SPARK_TESTING" -> "0",
          // But set SPARK_SQL_TESTING to make spark-class happy.
          "SPARK_SQL_TESTING" -> "1",
          // Points SPARK_PID_DIR to SPARK_HOME, otherwise only 1 Thrift server instance can be
          // started at a time, which is not Jenkins friendly.
          "SPARK_PID_DIR" -> pidDir.getCanonicalPath),
        redirectStderr = true)

      logInfo(s"COMMAND: $command")
      logInfo(s"OUTPUT: $lines")
      lines.split("\n").collectFirst {
        case line if line.contains(LOG_FILE_MARK) => new File(line.drop(LOG_FILE_MARK.length))
      }.getOrElse {
        throw new RuntimeException("Failed to find HiveThriftServer2 log file.")
      }
    }

    val serverStarted = Promise[Unit]()

    // Ensures that the following "tail" command won't fail.
    logPath.createNewFile()
    val successLines = Seq(THRIFT_BINARY_SERVICE_LIVE, THRIFT_HTTP_SERVICE_LIVE)

    logTailingProcess = {
      val command = s"/usr/bin/env tail -n +0 -f ${logPath.getCanonicalPath}".split(" ")
      // Using "-n +0" to make sure all lines in the log file are checked.
      val builder = new ProcessBuilder(command: _*)
      val captureOutput = (line: String) => diagnosisBuffer.synchronized {
        diagnosisBuffer += line

        successLines.foreach { r =>
          if (line.contains(r)) {
            serverStarted.trySuccess(())
          }
        }
      }

        val process = builder.start()

      new ProcessOutputCapturer(process.getInputStream, captureOutput).start()
      new ProcessOutputCapturer(process.getErrorStream, captureOutput).start()
      process
    }

    ThreadUtils.awaitResult(serverStarted.future, SERVER_STARTUP_TIMEOUT)
  }

  private def stopThriftServer(): Unit = {
    // The `spark-daemon.sh' script uses kill, which is not synchronous, have to wait for a while.
    Utils.executeAndGetOutput(
      command = Seq(stopScript),
      extraEnvironment = Map("SPARK_PID_DIR" -> pidDir.getCanonicalPath))
    Thread.sleep(3.seconds.toMillis)

    warehousePath.delete()
    warehousePath = null

    metastorePath.delete()
    metastorePath = null

    operationLogPath.delete()
    operationLogPath = null

    lScratchDir.delete()
    lScratchDir = null

    Option(logPath).foreach(_.delete())
    logPath = null

    Option(logTailingProcess).foreach(_.destroy())
    logTailingProcess = null
  }

  private def dumpLogs(): Unit = {
    logError(
      s"""
         |=====================================
         |HiveThriftServer2Suite failure output
         |=====================================
         |${diagnosisBuffer.mkString("\n")}
         |=========================================
         |End HiveThriftServer2Suite failure output
         |=========================================
       """.stripMargin)
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    // Chooses a random port between 10000 and 19999
    listeningPort = 10000 + Random.nextInt(10000)
    diagnosisBuffer.clear()

    // Retries up to 3 times with different port numbers if the server fails to start
    (1 to 3).foldLeft(Try(startThriftServer(listeningPort, 0))) { case (started, attempt) =>
      started.orElse {
        listeningPort += 1
        stopThriftServer()
        Try(startThriftServer(listeningPort, attempt))
      }
    }.recover {
      case cause: Throwable =>
        dumpLogs()
        throw cause
    }.get

    logInfo(s"HiveThriftServer2 started successfully")
  }

  override protected def afterAll(): Unit = {
    try {
      stopThriftServer()
      logInfo("HiveThriftServer2 stopped")
    } finally {
      super.afterAll()
    }
  }
}
