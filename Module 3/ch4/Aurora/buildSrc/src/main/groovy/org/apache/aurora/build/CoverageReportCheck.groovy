/**
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
package org.apache.aurora.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.TaskAction

/**
 * A task that analyzes the XML coverage report from JaCoCo and fails the build if code coverage
 * metrics are not met.
 */
class CoverageReportCheck extends DefaultTask {

  // The XML coverage report file.
  def coverageReportFile

  // Minimum ratio of instructions covered, [0, 1.0].
  def minInstructionCoverage

  // Minimum ratio of branches covered, [0, 1.0].
  def minBranchCoverage

  // Classes that may be allowed to have zero test coverage.
  def legacyClassesWithoutCoverage

  // The amount of wiggle room when requiring min coverage be raised.
  def epsilon = 0.005

  private def computeCoverage(counterNodes, type) {
    def node = counterNodes.find { it.@type == type }
    def missed = node.@missed.toInteger()
    def covered = node.@covered.toInteger()
    return ((double) covered) / (missed + covered)
  }

  def checkThresholds(coverage, minCoverage, type) {
    if (coverage < minCoverage) {
      return "$type coverage is $coverage, but must be greater than $minCoverage"
    } else {
      def floored = Math.floor(coverage * 100) / 100
      if (floored > (minCoverage + epsilon)) {
        println("$type coverage of $floored exceeds min instruction coverage of $minCoverage"
            + " by more than $epsilon, please raise the threshold!")
      } else {
        println("$type coverage of $coverage exceeds minimum coverage of $minCoverage.")
      }
    }
  }

  def checkGlobalCoverage(coverageCounts) {
    def coverageErrors = [
        [computeCoverage(coverageCounts, 'INSTRUCTION'), minInstructionCoverage, 'Instruction'],
        [computeCoverage(coverageCounts, 'BRANCH'), minBranchCoverage, 'Branch']
    ].collect() {
      return checkThresholds(*it)
    }.findAll()

    if (!coverageErrors.isEmpty()) {
      // We print here and don't fail the build since this metric has proven to be flaky,
      // and different JVMs can produce different results.
      println(coverageErrors.join('\n'))
    }
  }

  def checkClassCoverage(coverageReport) {
    def coverageErrors = coverageReport.package.class.collect { cls ->
      def matchedMethods = cls.method
          // Ignore static code, it should not count as test coverage.
          .findAll({ m -> m.@name != '<clinit>' })
          // Ignore classes that only have a constructor. This will avoid tripping for things like
          // constant-only utility classes, and 'value' classes like TypeLiteral and Clazz.
          .findAll({ m -> m.@name != '<init>' })

      // Ignore enums that contain only default methods.
      if (matchedMethods.collect { m -> m.@name } == ['values', 'valueOf']) {
        return null
      }

      if (matchedMethods.isEmpty()) {
        if (cls.@name in legacyClassesWithoutCoverage) {
          return 'Please remove ' + cls.@name + ' from the legacyClassesWithoutCoverage list' \
              + ', this check does not apply for constructor-only classes' \
              + ' or classes with only static class initialization code.'
        } else {
          return null
        }
      }

      def covered = matchedMethods.collect { m ->
        m.counter.find({ c -> c.@type == 'INSTRUCTION' }).@covered.toInteger()}.sum(0)

      if (cls.@name in legacyClassesWithoutCoverage) {
        if (covered != 0) {
          return 'Thanks for adding the first test coverage to: ' + cls.@name \
              + ' please remove it from the legacyClassesWithoutCoverage list'
        }
      } else if (covered == 0) {
        return 'Test coverage missing for ' + cls.@name
      }
      return null
    }.findAll()  // Filter nulls.
    if (!coverageErrors.isEmpty()) {
      throw new GradleException(coverageErrors.join('\n'))
    }
  }

  @TaskAction
  def analyze() {
    def parser = new XmlSlurper()
    parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
    // Avoid trying to load the DTD for the XML document, which does not exist.
    parser.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)

    def coverageReport = parser.parse(coverageReportFile)

    checkGlobalCoverage(coverageReport.counter)
    checkClassCoverage(coverageReport)
  }
}
