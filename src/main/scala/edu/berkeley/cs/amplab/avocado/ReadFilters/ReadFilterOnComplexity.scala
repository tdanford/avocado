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

package edu.berkeley.cs.amplab.avocado.filters.reads

import spark.{RDD,SparkContext}
import edu.berkeley.cs.amplab.adam.avro.ADAMRecord
import edu.berkeley.cs.amplab.avocado.filters.reads.ReadFilter
import scala.math.{min,max}

/**
 * Enumeration for levels of complexity (high, medium, low).
 */
object MapComplexity extends Enumeration {
  type MapComplexity = Value
  val High, Medium, Low = Value
}

import MapComplexity._

/**
 * Class that implements a complexity based read filter.
 */ 
class ReadFilterOnComplexity extends ReadFilter {

  val stripe = 1000
  val overlap = 100
  val mappingQualityThreshold = 30
  val coverageThreshold = 40

  /**
   * Calculates the complexity of a region. Uses the following heuristics:
   *  * Mapping Quality: What is the average mapping quality of this region?
   *  * Coverage: What is the average coverage over this region?
   * These values are compared against thresholds set in configuration.
   *
   * @param[in] reads A list of reads.
   * @return Tuple of region complexity and list of reads.
   */
  def scoreComplexity (segment: (Int, List[ADAMRecord])): (MapComplexity, List[ADAMRecord]) = {
    val (segmentNumber, reads) = segment
    val segmentStart = segment * stripe
    val segmentEnd = (segment + 1) * stripe
    val complexity = reads.map (_.mapq).reduce (_ + _) / reads.length
    val coverage = reads.map (min (_.end, segmentEnd) - max (_.start, segmentStart))
                        .reduce (_ + _) / reads.length

    if (complexity >= mappingQualityThreshold && coverage >= coverageThreshold) {
      return (High, reads)
    } else if (complexity >= mappingQualityThreshold || coverage >= coverageThreshold) {
      return (Medium, reads)
    } else {
      return (Low, reads)
    }
  }

  /**
   * Simple complexity filter. Looks at average mapping quality and pileup depth.
   *
   * @param[in] reads An RDD containing reads.
   * @return An RDD containing lists of reads.
   */
  def filter (reads: RDD [(Unit, ADAMRecord)]): RDD [(MapComplexity, List[ADAMRecord])] = {

    /* split into windows of reads
     * FIXME: add overlap on windows
     */
    val segments = reads.map (_ => (_.start / stripe, _))
			.groupByKey ()
    
    /* compute complexity value, group by complexity, and return */
    return segments.map (scoreComplexity).reduceByKey (_ :: _)
  }
}
