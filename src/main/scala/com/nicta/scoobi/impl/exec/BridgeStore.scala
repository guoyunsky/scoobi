/**
 * Copyright 2011,2012 National ICT Australia Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.nicta.scoobi
package impl
package exec

import org.apache.commons.logging.LogFactory
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.fs.FileStatus
import org.apache.hadoop.io.NullWritable
import org.apache.hadoop.io.SequenceFile
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat
import org.apache.hadoop.mapreduce.Job

import io._
import rtt._
import Configurations._
import application.ScoobiConfiguration
import application.ScoobiConfiguration._
import java.net.URLClassLoader

/** A bridge store is any data that moves between MSCRs. It must first be computed, but
  * may be removed once all successor MSCRs have consumed it. */
case class BridgeStore[A]()
  extends DataSource[NullWritable, ScoobiWritable[A], A]
  with DataSink[NullWritable, ScoobiWritable[A], A] {

  lazy val logger = LogFactory.getLog("scoobi.Bridge")

  /* rtClass will be created at runtime as part of building the MapReduce job. */
  var rtClass: Option[RuntimeClass] = None

  /**
   * this value is set by the configuration so as to be unique for this bridge store
   */
  lazy val id = java.util.UUID.randomUUID.toString
  lazy val typeName = "BS" + id
  def path(implicit sc: ScoobiConfiguration) = new Path(sc.workingDirectory, "bridges/" + id)

  /* Output (i.e. input to bridge) */
  val outputFormat = classOf[SequenceFileOutputFormat[NullWritable, ScoobiWritable[A]]]
  val outputKeyClass = classOf[NullWritable]
  def outputValueClass = rtClass.orNull.clazz.asInstanceOf[Class[ScoobiWritable[A]]]
  def outputCheck(implicit sc: ScoobiConfiguration) {}
  def outputConfigure(job: Job)(implicit sc: ScoobiConfiguration) {
    FileOutputFormat.setOutputPath(job, path)
  }
  lazy val outputConverter = new ScoobiWritableOutputConverter[A](typeName)


  /* Input (i.e. output of bridge) */
  val inputFormat = classOf[SequenceFileInputFormat[NullWritable, ScoobiWritable[A]]]
  def inputCheck(implicit sc: ScoobiConfiguration) {}
  def inputConfigure(job: Job)(implicit sc: ScoobiConfiguration) {
    FileInputFormat.addInputPath(job, new Path(path(sc), "ch*"))
  }

  def inputSize(implicit sc: ScoobiConfiguration): Long = Helper.pathSize(new Path(path, "ch*"))(sc)

  lazy val inputConverter = new InputConverter[NullWritable, ScoobiWritable[A], A] {
    def fromKeyValue(context: InputContext, key: NullWritable, value: ScoobiWritable[A]): A = value.get
  }


  /* Free up the disk space being taken up by this intermediate data. */
  def freePath(implicit sc: ScoobiConfiguration) {
    val fs = path.getFileSystem(sc)
    fs.delete(path, true)
  }


  /* Read the contents of this bridge store sequence files as an Iterable collection. The
   * undelying Iterator has a lazy implementation and will only bring one element into memory
   * at a time. */
  def readAsIterable(implicit sc: ScoobiConfiguration): Iterable[A] = new Iterable[A] {
    def iterator = new Iterator[A] {

      val fs = FileSystem.get(path.toUri, sc)
      val readers = fs.globStatus(new Path(path, "ch*")) map { (stat: FileStatus) =>
        new SequenceFile.Reader(sc, SequenceFile.Reader.file(stat.getPath))
      }

      val key = NullWritable.get

      /** instantiate a ScoobiWritable from the Writable class generated for this BridgeStore */
      lazy val value: ScoobiWritable[A] =
        rtClass.map(_.clazz).getOrElse(throw new Exception("No rt class is set on the BridgeStore "+this)).
          newInstance.asInstanceOf[ScoobiWritable[A]]

      var remainingReaders = readers.toList
      var empty = if (readers.isEmpty) true else !readNext()

      def next(): A = { val v = value.get; empty = !readNext(); v }
      def hasNext(): Boolean = !empty

      /* Attempt to read the next key-value and return true if successful, else false. As the
       * end of each SequenceFile.Reader is reached, move on to the next until they have all
       * been read. */
      def readNext(): Boolean = remainingReaders match {
        case cur :: rest => if (cur.next(key, value)) true else { remainingReaders = rest; readNext() }
        case Nil         => false
      }
    }
  }


  override def toString = typeName

  override def equals(other: Any) = {
    other match {
      case bs: BridgeStore[_] => bs.id == this.id
      case _                  => false
    }
  }

  override def hashCode = id.hashCode
}

/** OutputConverter for a bridges. The expectation is that by the time toKeyValue is called,
  * the Class for 'value' will exist and be known by the ClassLoader. */
class ScoobiWritableOutputConverter[A](typeName: String) extends OutputConverter[NullWritable, ScoobiWritable[A], A] {
  lazy val value: ScoobiWritable[A] = Class.forName(typeName).newInstance.asInstanceOf[ScoobiWritable[A]]
  def toKeyValue(x: A): (NullWritable, ScoobiWritable[A]) = {
    value.set(x)
    (NullWritable.get, value)
  }
}
