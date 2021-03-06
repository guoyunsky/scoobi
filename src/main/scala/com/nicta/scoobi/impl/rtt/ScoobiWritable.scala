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
package rtt

import org.apache.hadoop.io._
import core._
import java.io.{DataInput, DataOutput}
import application.ScoobiConfiguration

/** The super-class of all "value" types used in Hadoop jobs. */
abstract class ScoobiWritable[A](private var x: A) extends Writable { self =>
  def this() = this(null.asInstanceOf[A])
  def get: A = x
  def set(x: A) { self.x = x }
}


/** Constructs a ScoobiWritable, with some metadata (a WireFormat) retrieved from the distributed cache */
object ScoobiWritable {

  def apply(name: String, wf: WireFormat[_])(implicit sc: ScoobiConfiguration): RuntimeClass = writables(new NamedWritable(name) {
    def wireFormat = wf
    def configuration = sc
  })

  def apply[A](name: String, witness: A)(implicit sc: ScoobiConfiguration, wf: WireFormat[A]): RuntimeClass =
    apply(name, wf)

  /** this case class is just used to hold the name and make sure that equality is based on the name only in the memo map */
  private abstract case class NamedWritable(name: String) {
    def wireFormat: WireFormat[_]
    def configuration: ScoobiConfiguration
  }
  private lazy val writables = scalaz.Memo.mutableHashMapMemo[NamedWritable, RuntimeClass] { case nwt: NamedWritable =>
    MetadataClassBuilder[MetadataScoobiWritable](nwt.name, nwt.wireFormat)(nwt.configuration, implicitly[Manifest[MetadataScoobiWritable]]).toRuntimeClass
  }

}

abstract class MetadataScoobiWritable extends ScoobiWritable[Any] {

  def metadataPath: String

  lazy val wireFormat = ScoobiMetadata.metadata(metadataPath).asInstanceOf[WireFormat[Any]]

  def write(out: DataOutput) {
    wireFormat.toWire(get, out)
  }

  def readFields(in: DataInput) {
    set(wireFormat.fromWire(in))
  }

  override def toString = get.toString
}

