/*
 * Licensed to Big Data Genomics (BDG) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The BDG licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bdgenomics.avocado.postprocessing.mutect

import org.apache.spark.rdd.RDD
import org.bdgenomics.adam.models.VariantContext
import org.bdgenomics.formats.avro.AlignmentRecord
import org.bdgenomics.adam.rich.ReferenceMappingContext._
import ClassifiedContext._
import org.bdgenomics.adam.rdd.RegionRDDFunctions._

trait MutectPostprocessor extends Serializable {

  def filter(variants: RDD[VariantContext],
             reads: RDD[Classified[AlignmentRecord]]): RDD[VariantContext]

}

class ProximalIndelFilter(val indelReadThreshold: Int = 3,
                          val windowDistance: Int = 11) extends MutectPostprocessor with Serializable {

  def hasIndel(rec: AlignmentRecord): Boolean = ???

  override def filter(variants: RDD[VariantContext],
                      reads: RDD[Classified[AlignmentRecord]]): RDD[VariantContext] =
    variants
      .groupWithinRange(
        reads.filterByClasses("tumor", "retained").values(),
        windowDistance.toLong)
      .filter(_._2.count(hasIndel) < indelReadThreshold)
      .map(_._1)
}
