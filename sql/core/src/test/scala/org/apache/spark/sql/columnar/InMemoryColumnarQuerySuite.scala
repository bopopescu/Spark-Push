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

package org.apache.spark.sql.columnar

import org.apache.spark.sql.catalyst.expressions.Row
import org.apache.spark.sql.test.TestSQLContext
import org.apache.spark.sql.{QueryTest, TestData}
import org.apache.spark.storage.StorageLevel.MEMORY_ONLY

class InMemoryColumnarQuerySuite extends QueryTest {
  import org.apache.spark.sql.TestData._
  import org.apache.spark.sql.test.TestSQLContext._

  test("simple columnar query") {
    val plan = TestSQLContext.executePlan(testData.logicalPlan).executedPlan
    val scan = InMemoryRelation(useCompression = true, 5, MEMORY_ONLY, plan)

    scan.where('key > 20  || 'key < 50).where('key < 40).select('value as 'a).collect().foreach(println)
    //一个where对应一个predicate
    //scan.registerTempTable("scanData")

    //sql("select value from scanData where (key > 20 and key < 50) or key < 40").collect().foreach(println)

    //checkAnswer(scan, testData.collect().toSeq)
  }

  test("default size avoids broadcast") {
    // TODO: Improve this test when we have better statistics
    sparkContext.parallelize(1 to 10).map(i => TestData(i, i.toString)).registerTempTable("sizeTst")
    cacheTable("sizeTst")
    assert(
      table("sizeTst").queryExecution.logical.statistics.sizeInBytes > autoBroadcastJoinThreshold)
  }

  test("projection") {
    val plan = TestSQLContext.executePlan(testData.select('value, 'key).logicalPlan).executedPlan
    val scan = InMemoryRelation(useCompression = true, 5, MEMORY_ONLY, plan)

    checkAnswer(scan, testData.collect().map {
      case Row(key: Int, value: String) => value -> key
    }.toSeq)
  }

  test("SPARK-1436 regression: in-memory columns must be able to be accessed multiple times") {
    val plan = TestSQLContext.executePlan(testData.logicalPlan).executedPlan
    val scan = InMemoryRelation(useCompression = true, 5, MEMORY_ONLY, plan)

    checkAnswer(scan, testData.collect().toSeq)
    checkAnswer(scan, testData.collect().toSeq)
  }

  test("SPARK-1678 regression: compression must not lose repeated values") {
    checkAnswer(
      sql("SELECT * FROM repeatedData"),
      repeatedData.collect().toSeq)

    TestSQLContext.cacheTable("repeatedData")

    checkAnswer(
      sql("SELECT * FROM repeatedData"),
      repeatedData.collect().toSeq)
  }

  test("with null values") {
    checkAnswer(
      sql("SELECT * FROM nullableRepeatedData"),
      nullableRepeatedData.collect().toSeq)

    TestSQLContext.cacheTable("nullableRepeatedData")

    checkAnswer(
      sql("SELECT * FROM nullableRepeatedData"),
      nullableRepeatedData.collect().toSeq)
  }

  test("SPARK-2729 regression: timestamp data type") {
    checkAnswer(
      sql("SELECT time FROM timestamps"),
      timestamps.collect().toSeq)

    TestSQLContext.cacheTable("timestamps")

    checkAnswer(
      sql("SELECT time FROM timestamps"),
      timestamps.collect().toSeq)
  }

  test("SPARK-3320 regression: batched column buffer building should work with empty partitions") {
    checkAnswer(
      sql("SELECT * FROM withEmptyParts"),
      withEmptyParts.collect().toSeq)

    TestSQLContext.cacheTable("withEmptyParts")

    checkAnswer(
      sql("SELECT * FROM withEmptyParts"),
      withEmptyParts.collect().toSeq)
  }
}
