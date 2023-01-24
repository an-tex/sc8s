package net.sc8s.akka

import net.sc8s.akka.stream.StreamOps.{SourceImplicits, SourceWithContextImplicits}

package object stream {
  object implicits extends SourceImplicits with SourceWithContextImplicits
}
