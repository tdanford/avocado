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

import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD
import org.bdgenomics.adam.models.{ReferencePosition, ReferenceRegion, ReferenceMapping, VariantContext}
import org.bdgenomics.formats.avro.AlignmentRecord
import org.bdgenomics.adam.rich.ReferenceMappingContext._
import ClassifiedContext._
import org.bdgenomics.adam.rdd.RegionRDDFunctions._
import scala.math.max
import org.bdgenomics.adam.rich.RichAlignmentRecord._

trait MutectPostprocessor extends Serializable {

  def filter(variants: RDD[VariantContext],
             tumorReads: RDD[Classified[AlignmentRecord]],
             normalReads: RDD[Classified[AlignmentRecord]]): RDD[VariantContext]
}

object ProximalGapFilter {

  /**
   * Different descriptions of Mutect have different descriptions of this filter --
   * it's not clear whether it's
   *   (>= 3 reads with either an insertion or a deletion)
   * or
   *   (>= 3 reads with an insertion) or (>= 3 reads with a deletion)
   *
   * The paper seems to suggest the latter, but other sources (includes slides I've found)
   * suggest the former.
   *
   * So I'm abstracting it out, with a simple implementation here that can be parameterized
   * later.
   */
  def hasIndel(rec: AlignmentRecord): Boolean =
    rec.getCigar.contains("I") || rec.getCigar.contains("D")
}


/**
 * ProximalGapFilter implements the HC filter described by (in the paper) the following
 * text:
 *   Remove false positives caused by nearby misaligned small insertion and deletion events.
 *   Reject candidate site if there are ≥ 3 reads with insertions in an 11-base-pair window
 *   centered on the candidate mutation or if there are ≥ 3 reads with deletions in the same
 *   11-base-pair window.
 *
 * See the notes to the ProximalGapFilter.hasIndel method, above, for more details on some
 * ambiguity in this statement.
 *
 * @param indelReadThreshold The number of reads passing the filter below which the variant is
 *                           retained (by default == 3)
 * @param windowDistance The distance around the variant to look for overlapping reads
 *                       (by default == 11)
 * @param gapFilter The function that determines whether or not a read has an indel.
 */
class ProximalGapFilter(val indelReadThreshold: Int = 3,
                        val windowDistance: Int = 11,
                        val gapFilter : AlignmentRecord=>Boolean = ProximalGapFilter.hasIndel)
extends MutectPostprocessor with Serializable {

  override def filter(variants: RDD[VariantContext],
                      tumorReads: RDD[Classified[AlignmentRecord]],
                       normalReads : RDD[Classified[AlignmentRecord]]): RDD[VariantContext] =
    variants
      .groupWithinRange(
        tumorReads.filterByClasses("retained").values(),
        windowDistance.toLong)
      .filter(_._2.count(gapFilter) < indelReadThreshold)
      .map(_._1)
}

/**
 * Implements the Poor Mapping Filter, described in the paper with the following text:
 *
 *   Remove false positives caused by sequence similarity in the genome, leading to
 *   misplacement of reads. Two tests are used to identify such sites:
 *     (i) candidates are rejected if ≥ 50% of the reads in the tumor and normal
 *         samples have a mapping quality of zero (although reads with a mapping
 *         quality of zero are discarded in the short-read pre-processing (Supplementary
 *         Methods), this filter reconsiders those discarded reads); and
 *     (ii) candidates are rejected if they do not have at least a single observation
 *          of the mutant allele with a confident mapping (that is, mapping quality
 *          score ≥ 20).
 */
class PoorMappingFilter(val mapq0Fraction : Double=0.5,
                        val confidentMappingQuality : Int = 20) extends MutectPostprocessor with Serializable {

  // TODO(twd): need to review this, not sure a mismatch is the right standard for "observes mutant allele."
  def observesMutantAllele(vc : VariantContext)(read : AlignmentRecord) : Boolean = {
    read.isMismatchAtReferencePosition(vc.position).getOrElse(false)
  }

  override def filter(variants: RDD[VariantContext],
                      tumorReads: RDD[Classified[AlignmentRecord]],
                      normalReads: RDD[Classified[AlignmentRecord]]): RDD[VariantContext] = {

    val tumorMapqFractions : RDD[(VariantContext, (Int, Int) )] = variants
      .groupByOverlap(tumorReads.values())
      .map(p => (p._1, (p._2.count(r => r.getMapq == 0), p._2.size)))

    val normalMapqFractions : RDD[(VariantContext,( Int, Int) )] = variants
      .groupByOverlap(normalReads.values())
      .map(p => (p._1, (p._2.count(r => r.getMapq == 0), p._2.size)))

    // This captures the set of variants which _pass_ condition (i)
    val filter1 = tumorMapqFractions.join(normalMapqFractions)
      .filter {
      case (vc : VariantContext, counts : ((Int, Int), (Int, Int))) => {

        val zeroed = counts._1._1 + counts._2._1
        val total = counts._1._2 + counts._2._2
        val fraction = zeroed.toDouble / max(1, total)

        fraction < mapq0Fraction
      }
    }.map(_._1)

    // This captures the set of variants which _pass_ condition (ii)
    val filter2 = variants.groupByOverlap(tumorReads.filterByClasses("retained").values())
      .filter {
      case (vc : VariantContext, reads : Iterable[AlignmentRecord]) =>
        reads.exists(observesMutantAllele(vc))
    }.map(_._1)

    // And we return the intersection of the variants (i.e. that pass _both_ filters)
    filter1.intersection(filter2)
  }
}

/**
 * Based on the following text:
 *   Reject false positives caused by calling triallelic sites where the normal
 *   sample is heterozygous with alleles A and B, and MuTect is considering an
 *   alternate allele C. Although this is biologically possible, and remains an
 *   area for future improvement in the detection of mutations, calling at these
 *   sites generates many false positives, and therefore they are currently
 *   filtered out by default. However, it may be desirable to review mutations
 *   failing only this filter for biological relevance and orthogonal validation,
 *   and to study the underlying reasons for these false positives.
 */
class TriallelicSiteFilter extends MutectPostprocessor with Serializable {

  def isTriAllelic(vc : VariantContext, normalReads : Iterable[AlignmentRecord]) : Boolean =
    ???

  override def filter(variants: RDD[VariantContext],
                      tumorReads: RDD[Classified[AlignmentRecord]],
                      normalReads: RDD[Classified[AlignmentRecord]]): RDD[VariantContext] = {
    variants.groupByOverlap()
  }
}
