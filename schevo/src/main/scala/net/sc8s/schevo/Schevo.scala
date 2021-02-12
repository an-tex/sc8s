package net.sc8s.schevo

// define an object extending this trait
trait Schevo {
  // implement this as a trait
  type Latest <: LatestBase

  // point this to your case class
  type LatestCaseClass <: LatestBase

  trait LatestBase extends Revision {
    // esp. useful for _.copy
    def caseClass: LatestCaseClass
  }


  trait Revision extends Schevo.RevisionT[Latest] {
    type LatestTrait = Latest
  }
}

object Schevo {
  // this indirection is mainly for generic migration using pattern matching
  trait RevisionT[Latest] {
    def migrate: Latest
  }
}