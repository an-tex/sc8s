package net.sc8s.logstage.elastic

import izumi.logstage.api.IzLogger
import izumi.logstage.api.Log.CustomContext
import izumi.logstage.api.Log.Level.Debug
import izumi.logstage.api.rendering.logunits.Styler.PadType
import izumi.logstage.api.rendering.logunits.{Extractor, Renderer, Styler}
import izumi.logstage.api.rendering.{RenderingOptions, StringRenderingPolicy}
import izumi.logstage.sink.slf4j.LogSinkLegacySlf4jImpl

trait Logging {
  protected lazy val logContext: CustomContext = CustomContext()

  // used for event parameters & context prefixes
  protected lazy val loggerClass = this.getClass.getName.takeWhile(_ != '$')

  protected implicit lazy val log = {
    val renderPolicy =
      if (sys.props.get("logger.izumi.sink").contains("json"))
        LogstageCirceElasticRenderingPolicy(loggerClass)
      else
        new StringRenderingPolicy(RenderingOptions.default, Some(Logging.template))

    IzLogger(Debug, Seq(new LogSinkLegacySlf4jImpl(renderPolicy)))(logContext)
  }
}

object Logging {
  val template = new Renderer.Aggregate(Seq(
    new Styler.Colored(
      Console.BLUE,
      Seq(
        new Styler.AdaptivePad(Seq(new Extractor.SourcePosition()), 8, PadType.Left, ' ')
      )
    ),
    Extractor.Space,
    new Styler.TrailingSpace(Seq(new Extractor.LoggerContext())),
    new Extractor.Message(),
  ))
}
