package lila.evaluation

import Math.{pow, E, PI, log, sqrt, abs, exp, round}
import org.joda.time.DateTime
import scalaz.NonEmptyList
import chess.{ Color }
import lila.game.{ Pov, Game }
import lila.analyse.{ Accuracy, Analysis }

case class PlayerAssessment(
  _id: String,
  gameId: String,
  white: Boolean, // Side of the game being analysed
  assessment: Int, // 1 = Not Cheating, 2 = Unlikely Cheating, 3 = Unknown, 4 = Likely Cheating, 5 = Cheating
  by: String, // moderator ID
  date: DateTime) {
  val color = Color(white)
}

case class PeerGame(
  gameId: String,
  white: Boolean,
  positiveMatch: Boolean,
  matchPercentage: Int,
  assessment: Int
  ) {
  val color = Color(white)

  val explanation: String =
    (if (positiveMatch) "Matches " else "Partially matches ") + "with " +
    (assessment match {
      case 1 => "a non cheating"
      case 2 => "an unlikely cheating"
      case 3 => "an unclear"
      case 4 => "a likely cheating"
      case 5 => "a cheating"
      case _ => "Undefined"
    }) + " game at " + matchPercentage + "% confidence"
}

case class PlayerAggregateAssessment(
  gameGroupResults: List[GameGroupResult]
  ) {
  import Statistics.listSum
  def sumAssessment(x: Int): Int =
    listSum(gameGroupResults.map { result => 
      if (result.aggregate.assessment == x && (result.aggregate.positiveMatch || result.aggregate.confidence > 80)) 1
      else 0
    })

  val cheatingSum: Int = sumAssessment(5)
  val likelyCheatingSum: Int = sumAssessment(4)
  val unclearSum: Int = sumAssessment(3)

  val markPri: Boolean = cheatingSum >= 2
  val markSec: Boolean = cheatingSum + likelyCheatingSum >= 4
  val reportPri: Boolean = cheatingSum + likelyCheatingSum >= 2
  val reportSec: Boolean = cheatingSum + likelyCheatingSum + unclearSum >= 4
}

case class AggregateAssessment(
    assessment: Int,
    confidence: Int,
    positiveMatch: Boolean
  ) {
  val explanation: String =
    (if (positiveMatch) "Matches " else "Partially matches ") +
    (assessment match {
      case 1 => "Not Cheating"
      case 2 => "Unlikely Cheating"
      case 3 => "Unclear"
      case 4 => "Likely Cheating"
      case 5 => "Cheating"
      case _ => "Undefined"
    }) + " at " + confidence + "% confidence"
}

case class GameGroupResult(
  _id: String, // sourceGameId + "/" + sourceGameColor
  userId: String, // The userId of the player being evaluated
  gameId: String, // The game being talked about
  white: Boolean, // The side of the game being talked about
  bestMatch: PeerGame,
  secondaryMatches: List[PeerGame],
  date: DateTime,
  // Meta infos
  sfAvg: Int,
  sfSd: Int,
  mtAvg: Int,
  mtSd: Int,
  blur: Int,
  hold: Boolean
  ) {
  import Statistics.{listSum, listAverage}
  val color = Color(white)
  val aggregate: AggregateAssessment = {
    val peers = bestMatch :: secondaryMatches
    AggregateAssessment(
      round(listSum(peers.map {
        case a if (a.positiveMatch) => 4 * a.matchPercentage * a.assessment
        case a => a.matchPercentage * a.assessment
      }).toDouble / listSum(peers.map {
        case a if (a.positiveMatch) => 4 * a.matchPercentage
        case a => a.matchPercentage
      })).toInt,
      bestMatch.matchPercentage,
      peers.exists(_.positiveMatch)
    )
  }
}

object Display {
  def assessmentString(x: Int): String = 
    x match {
      case 1 => "Not Cheating" // Not cheating
      case 2 => "Unlikely Cheating" // Unlikely Cheating
      case 3 => "Unclear" // Unclear
      case 4 => "Likely Cheating" // Likely Cheating
      case 5 => "Cheating" // Cheating
      case _ => "Undef"
    }

  def dataIcon(positiveMatch: Boolean, confidence: Int): String = {
    (positiveMatch, confidence) match {
      case (true, _) => "J"
      case (_, a) if (a >= 80) => "l"
      case _ => "K"
    }

  }
  def dataIcon(x: AggregateAssessment): String = dataIcon(x.positiveMatch, x.confidence)
  def dataIcon(x: PeerGame): String = dataIcon(x.positiveMatch, x.matchPercentage)
}


case class GameResults(
  white: Option[GameGroupResult],
  black: Option[GameGroupResult]
  ) {

  def color(c: Color): Option[GameGroupResult] = c.fold(white, black)
}

case class Rating(perf: Int, interval: Int)

case class Similarity(a: Double, threshold: Double = 0.9) {
  def apply: Double = a.min(1).max(0)

  val matches: Boolean = this.apply >= threshold
}

case class Analysed(game: Game, analysis: Analysis)

case class MatchAndSig(matches: Boolean, significance: Double)

case class GameGroup(analysed: Analysed, color: Color, assessment: Option[Int] = None) {
  import Statistics._

  def compareMoveTimes (that: GameGroup): Similarity = {
    val thisMt: List[Int] = skip(this.analysed.game.moveTimes.toList, {if (this.color == Color.White) 0 else 1})
    val thatMt: List[Int] = skip(that.analysed.game.moveTimes.toList, {if (that.color == Color.White) 0 else 1})

    listToListSimilarity(thisMt, thatMt, 0.8)
  }

  def compareSfAccuracies (that: GameGroup): (Similarity, Similarity) = {
    def groupedDiffList(game: Game, color: Color, analysis: Analysis, size: Int = 5): List[List[Int]] =
      Accuracy.diffsList(Pov(game, color), analysis).grouped(size).toList
    // Insist that is greater than this (so this can be compared in full saturation)
    val thisPlayerDiffs = groupedDiffList(this.analysed.game, this.color, this.analysed.analysis)
    val thatPlayerDiffs = groupedDiffList(that.analysed.game, that.color, that.analysed.analysis)
    if (thisPlayerDiffs.size != thatPlayerDiffs.size) return (Similarity(0), Similarity(0))
    else {
      (
        thisPlayerDiffs zip thatPlayerDiffs map {
          a => listToListSimilarity(a._1, a._2, 0.8)
        }
      ,
        (groupedDiffList(this.analysed.game, !this.color, this.analysed.analysis) zip
        groupedDiffList(that.analysed.game, !that.color, that.analysed.analysis)) map {       
          a => listToListSimilarity(a._1, a._2, 0.6)
        }
      ) match {
        case (Nil, Nil)                   => (Similarity(0), Similarity(0)) // Both empty
        case (Nil, a :: _)                => (Similarity(0), Similarity(0)) // One empty, The other with some
        case (a :: _, Nil)                => (Similarity(0), Similarity(0))
        case (a :: Nil, b :: Nil)         => (a, b)
        case (a :: Nil, b :: c)           => {
          val ssdA = ssd(b, c)
          (a, Similarity(ssdA, if (allSimilar(b, c)) (ssdA - 0.01) else (ssdA + 0.01)))
        }
        case (a :: b, c :: Nil)           => {
          val ssdA = ssd(a, b)
          (c, Similarity(ssdA, if (allSimilar(a, b)) (ssdA - 0.01) else (ssdA + 0.01)))
        }
        case (a :: b, c :: d)             => {
          val ssdA = ssd(a, b)
          val ssdB = ssd(c, d)
          (
            Similarity(ssdA, if (allSimilar(a, b)) (ssdA - 0.01) else (ssdA + 0.01)),
            Similarity(ssdB, if (allSimilar(c, d)) (ssdB - 0.01) else (ssdB + 0.01))
          )
        }
      }
    }
  }

  def compareBlurRates (that: GameGroup): Similarity = pointToPointSimilarity(
    (200 * this.analysed.game.player(this.color).blurs / this.analysed.game.turns).toInt,
    (200 * that.analysed.game.player(that.color).blurs / that.analysed.game.turns).toInt,
    5d
    )

  def compareHoldAlerts (that: GameGroup): Similarity = Similarity(
      if (this.analysed.game.player(this.color).hasSuspiciousHoldAlert == that.analysed.game.player(that.color).hasSuspiciousHoldAlert) 1 else 0,
      0.9
    )

  val sfAvg: Int = listAverage(Accuracy.diffsList(Pov(this.analysed.game, this.color), this.analysed.analysis)).toInt
  val sfSd: Int = listDeviation(Accuracy.diffsList(Pov(this.analysed.game, this.color), this.analysed.analysis)).toInt
  val mtAvg: Int = listAverage(skip(this.analysed.game.moveTimes.toList, {if (this.color == Color.White) 0 else 1})).toInt
  val mtSd: Int = listDeviation(skip(this.analysed.game.moveTimes.toList, {if (this.color == Color.White) 0 else 1})).toInt
  val blurs: Int = (200 * this.analysed.game.player(this.color).blurs / this.analysed.game.turns).toInt;
  val hold: Boolean = this.analysed.game.player(this.color).hasSuspiciousHoldAlert

  def similarityTo (that: GameGroup): MatchAndSig = {
    // Calls compare functions to determine how similar `this` and `that` are to each other
    val sfComparison = compareSfAccuracies(that)

    val similarities = NonEmptyList(
      compareMoveTimes(that),
      sfComparison._1,
      sfComparison._2,
      compareBlurRates(that),
      compareHoldAlerts(that)
    )

    MatchAndSig(
      allSimilar(similarities), // Are they all similar?
      ssd(similarities) // How significant is the similarity?
    )
  }
}

object Statistics {
  import Erf._
  import scala.annotation._

  def variance[T](a: NonEmptyList[T], optionalAvg: Option[T] = None)(implicit n: Numeric[T]): Double = {
    val avg: Double = optionalAvg.fold(average(a)){n.toDouble}

    a.map( i => pow(n.toDouble(i) - avg, 2)).list.sum / a.length
  }

  def deviation[T](a: NonEmptyList[T], optionalAvg: Option[T] = None)(implicit n: Numeric[T]): Double = sqrt(variance(a, optionalAvg))

  def average[T](a: NonEmptyList[T])(implicit n: Numeric[T]): Double = {
    @tailrec def average(a: List[T], sum: T = n.zero, depth: Int = 0): Double = {
      a match {
        case List()  => n.toDouble(sum) / depth
        case x :: xs => average(xs, n.plus(sum, x), depth + 1)
      }
    }
    average(a.list)
  }

  def setToSetSimilarity(avgA: Double, avgB: Double, varA: Double, varB: Double, threshold: Double): Similarity = {
    val sim = Similarity(
      pow(E, (-0.25) * ( log( 0.25 * ((varA / varB) + (varB / varA) + 2) ) + pow(avgA - avgB, 2) / ( varA + varB ) )),
      threshold)

    if (sim.a.isNaN || sim.a.isInfinity) Similarity(1, threshold)
    else sim
  }

  // Bhattacharyya Coefficient
  def setToSetSimilarity[T](a: NonEmptyList[T], b: NonEmptyList[T], threshold: Double = 0.9)(implicit n: Numeric[T]): Similarity = {
    val aDouble: NonEmptyList[Double] = a.map(n.toDouble)
    val bDouble: NonEmptyList[Double] = b.map(n.toDouble)

    val avgA = average(a)
    val avgB = average(b)

    val varA = pow(variance(aDouble, Some(avgA)), 2)
    val varB = pow(variance(bDouble, Some(avgB)), 2)

    setToSetSimilarity(avgA, avgB, varA, varB, threshold)
  }

  def listToListSimilarity[T](x: List[T], y: List[T], threshold: Double = 0.9)(implicit n: Numeric[T]): Similarity =
    (x, y) match {
      case (Nil, Nil)                   => Similarity(1) // Both empty
      case (Nil, _ :: _)                => Similarity(0) // One empty, The other with some
      case (_ :: _, Nil)                => Similarity(0)
      case (a :: Nil, b :: Nil)         => pointToPointSimilarity(a, b, 5d) // Both have one
      case (a :: Nil, b :: c)           => pointToSetSimilarity(a, NonEmptyList.nel(b, c)) // One with one element, the other with many
      case (a :: b, c :: Nil)           => pointToSetSimilarity(c, NonEmptyList.nel(a, b))
      case (a :: b, c :: d)             => setToSetSimilarity(NonEmptyList.nel(a, b), NonEmptyList.nel(c, d), threshold) // Both have many
    }

  def pointToSetSimilarity[T](x: T, set: NonEmptyList[T])(implicit n: Numeric[T]): Similarity = Similarity(
    confInterval(n.toDouble(x), average(set), sqrt(variance(set))),
    0.9
  )

  def pointToPointSimilarity[T](a: T, b: T, variance: Double)(implicit n: Numeric[T]): Similarity = Similarity(
    (a, b) match {
      case (a, b) if (a == b || n.toDouble(n.abs(n.minus(a, b))) < variance) => 1
      case _                                                                 => 0
    }
  )

  // Coefficient of Variance
  def coefVariation(a: NonEmptyList[Int]): Double = sqrt(variance(a)) / average(a)

  def intervalToVariance4(interval: Double): Double = pow(interval / 3, 8) // roughly speaking

  // Accumulative probability function for normal distributions
  def cdf[T](x: T, avg: T, sd: T)(implicit n: Numeric[T]): Double =
    0.5 * (1 + erf(n.toDouble(n.minus(x, avg)) / (n.toDouble(sd)*sqrt(2))))

  // The probability that you are outside of abs(x-n) from the mean on both sides
  def confInterval[T](x: T, avg: T, sd: T)(implicit n: Numeric[T]): Double =
    1 - cdf(n.abs(x), avg, sd) + cdf(n.times(n.fromInt(-1), n.abs(x)), avg, sd)

  // all Similarities in the non empty list are similar
  def allSimilar(a: NonEmptyList[Similarity]): Boolean = a.list.forall( _.matches )
  def allSimilar(a: Similarity, b: List[Similarity]): Boolean = allSimilar(NonEmptyList.nel(a, b))

  // Square Sum Distance
  def ssd(a: NonEmptyList[Similarity]): Double = sqrt(a.map(x => pow(x.apply, 2)).list.sum / a.size)
  def ssd(a: Similarity, b: List[Similarity]): Double = ssd(NonEmptyList.nel(a, b))

  def skip[A](l: List[A], n: Int) =
    l.zipWithIndex.collect {case (e,i) if ((i+n) % 2) == 0 => e} // (i+1) because zipWithIndex is 0-based


  def listSum(xs: List[Int]): Int = xs match {
    case Nil => 0
    case x :: tail => x + listSum(tail)
  }

  def listAverage[T](x: List[T])(implicit n: Numeric[T]): Double = x match {
    case Nil      => 0
    case a :: Nil => n.toDouble(a)
    case a :: b   => average(NonEmptyList.nel(a, b))
  }

  def listDeviation[T](x: List[T])(implicit n: Numeric[T]): Double = x match {
    case Nil      => 0
    case _ :: Nil => 0
    case a :: b   => deviation(NonEmptyList.nel(a, b))
  }
}

object Erf {
  // constants
  val a1: Double =  0.254829592
  val a2: Double = -0.284496736
  val a3: Double =  1.421413741
  val a4: Double = -1.453152027
  val a5: Double =  1.061405429
  val p: Double  =  0.3275911

  def erf(x: Double): Double = {
    // Save the sign of x
    val sign = if (x < 0) -1 else 1
    val absx = abs(x)

    // A&S formula 7.1.26, rational approximation of error function
    val t = 1.0/(1.0 + p*absx);
    val y = 1.0 - (((((a5*t + a4)*t) + a3)*t + a2)*t + a1)*t*exp(-x*x);
    sign*y
  }
}
