package net.sc8s.schevo

// define an object extending this trait
trait Schevo {
  // point this to your case class
  type LatestCaseClass <: LatestT

  trait LatestT extends VersionT {
    // esp. useful for _.copy
    def caseClass: LatestCaseClass
  }

  trait VersionT extends Schevo.VersionBase[LatestT] {
    type LatestTrait = LatestT
  }
}

object Schevo {
  // this indirection is mainly for generic evolution using pattern matching
  trait VersionBase[Latest] {
    def evolve: Latest
  }
}