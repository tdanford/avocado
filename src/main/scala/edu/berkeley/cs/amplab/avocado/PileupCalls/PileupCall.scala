/*
 * Copyright (c) 2013. Regents of the University of California
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

package edu.berkeley.cs.amplab.avocado.calls.pileup

import spark.{RDD,SparkContext}
import edu.berkeley.cs.amplab.adam.util.{Pileup,PileupTraversable}
import edu.berkeley.cs.amplab.adam.avro.ADAMVariant
import org.streum.configrity._

/**
 * Abstract class for calling variants on reads. 
 */
abstract class PileupCall (val callName: String,
			   val config: Configuration) {

  /**
   * Method signature for variant calling operation.
   *
   * @param[in] pileupGroups An RDD containing lists of pileups.
   * @return An RDD containing called variants.
   */
  def call (pileupGroups: RDD [(Unit, List[Pileup])]): RDD [ADAMVariant] 
}

