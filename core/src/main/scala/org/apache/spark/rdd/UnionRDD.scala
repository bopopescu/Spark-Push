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

package org.apache.spark.rdd

import java.io.{IOException, ObjectOutputStream}

import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag

import org.apache.spark.{Dependency, Partition, RangeDependency, SparkContext, TaskContext}
import org.apache.spark.annotation.DeveloperApi

/**
 * Partition for UnionRDD.
 *
 * @param idx index of the partition
 * @param rdd the parent RDD this partition refers to
 * @param parentRddIndex index of the parent RDD this partition refers to
 * @param parentRddPartitionIndex index of the partition within the parent RDD
 *                                this partition refers to
 */
private[spark] class UnionPartition[T: ClassTag](
    idx: Int,
    @transient rdd: RDD[T],
    val parentRddIndex: Int,
    @transient parentRddPartitionIndex: Int)
  extends Partition {

  var parentPartition: Partition = rdd.partitions(parentRddPartitionIndex)

  def preferredLocations() = rdd.preferredLocations(parentPartition)

  override val index: Int = idx

  @throws(classOf[IOException])
  private def writeObject(oos: ObjectOutputStream) {
    // Update the reference to parent split at the time of task serialization
    parentPartition = rdd.partitions(parentRddPartitionIndex)
    oos.defaultWriteObject()
  }
}

@DeveloperApi
class UnionRDD[T: ClassTag](
    sc: SparkContext,
    var rdds: Seq[RDD[T]])
  extends RDD[T](sc, Nil) {  // Nil since we implement getDependencies

  override def getPartitions: Array[Partition] = {
    val array = new Array[Partition](rdds.map(_.partitions.size).sum)
    var pos = 0
    for ((rdd, rddIndex) <- rdds.zipWithIndex; split <- rdd.partitions) {
      array(pos) = new UnionPartition(pos, rdd, rddIndex, split.index)
      pos += 1
    }
    array
  }

  override def getDependencies: Seq[Dependency[_]] = {
    val deps = new ArrayBuffer[Dependency[_]]
    var pos = 0
    for (rdd <- rdds) {
      deps += new RangeDependency(rdd, 0, pos, rdd.partitions.size)
      pos += rdd.partitions.size
    }
    deps
  }

  override def compute(s: Partition, context: TaskContext): Iterator[T] = {
    val part = s.asInstanceOf[UnionPartition[T]]
    parent[T](part.parentRddIndex).iterator(part.parentPartition, context)
  }

  override def getPreferredLocations(s: Partition): Seq[String] =
    s.asInstanceOf[UnionPartition[T]].preferredLocations()

  override def clearDependencies() {
    super.clearDependencies()
    rdds = null
  }

  //zengdan
  override def getParentRDD(s: Partition):Option[RDD[_]] = {
    val part = s.asInstanceOf[UnionPartition[T]]
    Option(parent[T](part.parentRddIndex))
  }

  override def getParentPartition(s: Partition):Partition = s.asInstanceOf[UnionPartition[T]].parentPartition

  def af(arr: Array[T]):Array[T] = {
    arr
  }

  def sf(x: T):T = {
    x
  }


  override def linkSingleFunc(s: Partition) = {
    //fcs = prev.fcs.andThen(ff)
    //curf = prev.curf.andThen(f2)
    val part = s.asInstanceOf[UnionPartition[T]]
    singlef.put(s, parent[T](part.parentRddIndex).singlef.get(s).andThen(sf))
  }

  override def linkArrayFunc(s: Partition) = {
    val part = s.asInstanceOf[UnionPartition[T]]
    arrayf.put(s, parent[T](part.parentRddIndex).arrayf.get(s).andThen(af))
  }
}
