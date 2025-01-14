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

package org.apache.spark.sql.execution.joins

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.BindReferences.bindReferences
import org.apache.spark.sql.catalyst.plans._
import org.apache.spark.sql.catalyst.plans.physical.Partitioning
import org.apache.spark.sql.execution.{ExplainUtils, RowIterator, SparkPlan}
import org.apache.spark.sql.execution.metric.SQLMetric
import org.apache.spark.sql.types.{IntegralType, LongType}

trait HashJoin {
  self: SparkPlan =>

  val leftKeys: Seq[Expression]
  val rightKeys: Seq[Expression]
  val joinType: JoinType
  val buildSide: BuildSide
  val condition: Option[Expression]
  val left: SparkPlan
  val right: SparkPlan

  override def simpleStringWithNodeId(): String = {
    val opId = ExplainUtils.getOpId(this)
    s"$nodeName $joinType ${buildSide} ($opId)".trim
  }

  override def verboseStringWithOperatorId(): String = {
    val joinCondStr = if (condition.isDefined) {
      s"${condition.get}"
    } else "None"

    s"""
       |$formattedNodeName
       |${ExplainUtils.generateFieldString("Left keys", leftKeys)}
       |${ExplainUtils.generateFieldString("Right keys", rightKeys)}
       |${ExplainUtils.generateFieldString("Join condition", joinCondStr)}
       |""".stripMargin
  }

  override def output: Seq[Attribute] = {
    joinType match {
      case _: InnerLike =>
        left.output ++ right.output
      case LeftOuter =>
        left.output ++ right.output.map(_.withNullability(true))
      // Add by 4Paradigm
      case LastJoinType =>
        left.output ++ right.output.map(_.withNullability(true))
      case RightOuter =>
        left.output.map(_.withNullability(true)) ++ right.output
      case j: ExistenceJoin =>
        left.output :+ j.exists
      case LeftExistence(_) =>
        left.output
      case x =>
        throw new IllegalArgumentException(s"HashJoin should not take $x as the JoinType")
    }
  }

  override def outputPartitioning: Partitioning = streamedPlan.outputPartitioning

  protected lazy val (buildPlan, streamedPlan) = buildSide match {
    case BuildLeft => (left, right)
    case BuildRight => (right, left)
  }

  protected lazy val (buildKeys, streamedKeys) = {
    require(leftKeys.map(_.dataType) == rightKeys.map(_.dataType),
      "Join keys from two sides should have same types")
    val lkeys = bindReferences(HashJoin.rewriteKeyExpr(leftKeys), left.output)
    val rkeys = bindReferences(HashJoin.rewriteKeyExpr(rightKeys), right.output)
    buildSide match {
      case BuildLeft => (lkeys, rkeys)
      case BuildRight => (rkeys, lkeys)
    }
  }



  protected def buildSideKeyGenerator(): Projection =
    UnsafeProjection.create(buildKeys)

  protected def streamSideKeyGenerator(): UnsafeProjection =
    UnsafeProjection.create(streamedKeys)

  @transient private[this] lazy val boundCondition = if (condition.isDefined) {
    Predicate.create(condition.get, streamedPlan.output ++ buildPlan.output).eval _
  } else {
    (r: InternalRow) => true
  }

  protected def createResultProjection(): (InternalRow) => InternalRow = joinType match {
    case LeftExistence(_) =>
      UnsafeProjection.create(output, output)
    case _ =>
      // Always put the stream side on left to simplify implementation
      // both of left and right side could be null
      UnsafeProjection.create(
        output, (streamedPlan.output ++ buildPlan.output).map(_.withNullability(true)))
  }

  private def innerJoin(
      streamIter: Iterator[InternalRow],
      hashedRelation: HashedRelation): Iterator[InternalRow] = {
    val joinRow = new JoinedRow
    val joinKeys = streamSideKeyGenerator()
    streamIter.flatMap { srow =>
      joinRow.withLeft(srow)
      val matches = hashedRelation.get(joinKeys(srow))
      if (matches != null) {
        matches.map(joinRow.withRight(_)).filter(boundCondition)
      } else {
        Seq.empty
      }
    }
  }

  private def outerJoin(
      streamedIter: Iterator[InternalRow],
    hashedRelation: HashedRelation,
    isLastJoin: Boolean = false): Iterator[InternalRow] = {
    val joinedRow = new JoinedRow()
    val keyGenerator = streamSideKeyGenerator()
    val nullRow = new GenericInternalRow(buildPlan.output.length)

    streamedIter.flatMap { currentRow =>
      val rowKey = keyGenerator(currentRow)
      joinedRow.withLeft(currentRow)
      val buildIter = hashedRelation.get(rowKey)
      new RowIterator {
        private var found = false
        override def advanceNext(): Boolean = {

          // Add by 4Paradigm to support last join
          if (isLastJoin && found) {
            return false
          }

          // Add by 4Paradigm to support last join
          if (isLastJoin) {
            if (buildIter != null && buildIter.hasNext) {
              val nextBuildRow = buildIter.next()
              if (boundCondition(joinedRow.withRight(nextBuildRow))) {
                found = true
                return true
              }
            }
          } else {
            while (buildIter != null && buildIter.hasNext) {
              val nextBuildRow = buildIter.next()
              if (boundCondition(joinedRow.withRight(nextBuildRow))) {
                found = true
                return true
              }
            }
          }

          if (!found) {
            joinedRow.withRight(nullRow)
            found = true
            return true
          }
          false
        }
        override def getRow: InternalRow = joinedRow
      }.toScala
    }
  }

  private def semiJoin(
      streamIter: Iterator[InternalRow],
      hashedRelation: HashedRelation): Iterator[InternalRow] = {
    val joinKeys = streamSideKeyGenerator()
    val joinedRow = new JoinedRow
    streamIter.filter { current =>
      val key = joinKeys(current)
      lazy val buildIter = hashedRelation.get(key)
      !key.anyNull && buildIter != null && (condition.isEmpty || buildIter.exists {
        (row: InternalRow) => boundCondition(joinedRow(current, row))
      })
    }
  }

  private def existenceJoin(
      streamIter: Iterator[InternalRow],
      hashedRelation: HashedRelation): Iterator[InternalRow] = {
    val joinKeys = streamSideKeyGenerator()
    val result = new GenericInternalRow(Array[Any](null))
    val joinedRow = new JoinedRow
    streamIter.map { current =>
      val key = joinKeys(current)
      lazy val buildIter = hashedRelation.get(key)
      val exists = !key.anyNull && buildIter != null && (condition.isEmpty || buildIter.exists {
        (row: InternalRow) => boundCondition(joinedRow(current, row))
      })
      result.setBoolean(0, exists)
      joinedRow(current, result)
    }
  }

  private def antiJoin(
      streamIter: Iterator[InternalRow],
      hashedRelation: HashedRelation): Iterator[InternalRow] = {
    val joinKeys = streamSideKeyGenerator()
    val joinedRow = new JoinedRow
    streamIter.filter { current =>
      val key = joinKeys(current)
      lazy val buildIter = hashedRelation.get(key)
      key.anyNull || buildIter == null || (condition.isDefined && !buildIter.exists {
        row => boundCondition(joinedRow(current, row))
      })
    }
  }

  protected def join(
      streamedIter: Iterator[InternalRow],
      hashed: HashedRelation,
      numOutputRows: SQLMetric): Iterator[InternalRow] = {

    val joinedIter = joinType match {
      case _: InnerLike =>
        innerJoin(streamedIter, hashed)
      case LeftOuter | RightOuter =>
        outerJoin(streamedIter, hashed)
      case LastJoinType =>
        // Add by 4Paradigm
        outerJoin(streamedIter, hashed, true)
      case LeftSemi =>
        semiJoin(streamedIter, hashed)
      case LeftAnti =>
        antiJoin(streamedIter, hashed)
      case j: ExistenceJoin =>
        existenceJoin(streamedIter, hashed)
      case x =>
        throw new IllegalArgumentException(
          s"BroadcastHashJoin should not take $x as the JoinType")
    }

    val resultProj = createResultProjection
    joinedIter.map { r =>
      numOutputRows += 1
      resultProj(r)
    }
  }
}

object HashJoin {
  /**
   * Try to rewrite the key as LongType so we can use getLong(), if they key can fit with a long.
   *
   * If not, returns the original expressions.
   */
  def rewriteKeyExpr(keys: Seq[Expression]): Seq[Expression] = {
    assert(keys.nonEmpty)
    // TODO: support BooleanType, DateType and TimestampType
    if (keys.exists(!_.dataType.isInstanceOf[IntegralType])
      || keys.map(_.dataType.defaultSize).sum > 8) {
      return keys
    }

    var keyExpr: Expression = if (keys.head.dataType != LongType) {
      Cast(keys.head, LongType)
    } else {
      keys.head
    }
    keys.tail.foreach { e =>
      val bits = e.dataType.defaultSize * 8
      keyExpr = BitwiseOr(ShiftLeft(keyExpr, Literal(bits)),
        BitwiseAnd(Cast(e, LongType), Literal((1L << bits) - 1)))
    }
    keyExpr :: Nil
  }

  /**
   * Extract a given key which was previously packed in a long value using its index to
   * determine the number of bits to shift
   */
  def extractKeyExprAt(keys: Seq[Expression], index: Int): Expression = {
    // jump over keys that have a higher index value than the required key
    if (keys.size == 1) {
      assert(index == 0)
      Cast(BoundReference(0, LongType, nullable = false), keys(index).dataType)
    } else {
      val shiftedBits =
        keys.slice(index + 1, keys.size).map(_.dataType.defaultSize * 8).sum
      val mask = (1L << (keys(index).dataType.defaultSize * 8)) - 1
      // build the schema for unpacking the required key
      Cast(BitwiseAnd(
        ShiftRightUnsigned(BoundReference(0, LongType, nullable = false), Literal(shiftedBits)),
        Literal(mask)), keys(index).dataType)
    }
  }
}
