package net.sc8s.schevo

// define an object extending this trait
trait Schevo {
  // point this to your case class
  type LatestCaseClass <: Latest

  trait Latest extends Revision {
    // esp. useful for _.copy
    def caseClass: LatestCaseClass
  }

  trait Revision extends Schevo.RevisionBase[Latest] {
    type LatestTrait = Latest
  }
}

object Schevo {
  // this indirection is mainly for generic migration using pattern matching
  trait RevisionBase[Latest] {
    def evolve: Latest
  }
}