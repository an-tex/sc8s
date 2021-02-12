package net.sc8s.schevo

// define an object extending this trait
trait Schevo {
  // point this to your case class
  type LatestCaseClass <: Latest

  trait Latest extends Version {
    // esp. useful for _.copy
    def caseClass: LatestCaseClass
  }

  trait Version extends Schevo.VersionBase[Latest] {
    type LatestTrait = Latest
  }
}

object Schevo {
  // this indirection is mainly for generic evolution using pattern matching
  trait VersionBase[Latest] {
    def evolve: Latest
  }
}