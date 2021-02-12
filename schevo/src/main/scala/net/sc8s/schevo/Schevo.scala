package net.sc8s.schevo

trait Schevo {
  type Latest <: {
    def caseClass: LatestCaseClass

    // add this to your trait in case you need trait based serialization e.g. with circe
    //def asTrait : Latest = this
  }

  type LatestCaseClass <: Latest

  trait Previous {
    def migrate: Latest
  }
}
