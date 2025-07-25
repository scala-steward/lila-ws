package lila.ws
package evalCache

import EvalCacheEntry.*

// selects the evals to store for a given position
private object EvalCacheSelector:

  private type Evals = List[Eval]

  def apply(evals: Evals): Evals =
    // first, let us group evals by multiPv
    evals
      .groupBy(_.multiPv)
      .toList
      // and sort the groups by multiPv, higher first
      .sortBy(-_._1)(using intOrdering)
      // keep only the best eval in each group
      .flatMap:
        import cats.syntax.all.*
        _._2.maximumByOption(ranking)
      // now remove obsolete evals
      .foldLeft(Nil: Evals):
        case (acc, e) if acc.exists { makesObsolete(_, e) } => acc
        case (acc, e) => e :: acc
      // and finally ensure ordering by depth and nodes, best first
      .sortBy(negativeNodesAndDepth)

  private def greatTrust(t: Trust) = t.value >= 5

  private def ranking(e: Eval): (Double, Double, Double) =
    // if well trusted, only rank on depth and tie on nodes
    if greatTrust(e.trust) then (99999, e.depth.value, e.knodes.value)
    // else, rank on trust, and tie on depth then nodes
    else (e.trust.value, e.depth.value, e.knodes.value)

  //     {multiPv:4,depth:30} makes {multiPv:2,depth:25} obsolete,
  // but {multiPv:2,depth:30} does not make {multiPv:4,depth:25} obsolete
  private def makesObsolete(a: Eval, b: Eval) =
    a.multiPv > b.multiPv && a.depth >= b.depth

  // for sorting
  def negativeNodesAndDepth(e: Eval) = (-e.depth.value, -e.knodes.value)
