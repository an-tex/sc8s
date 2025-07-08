package net.sc8s.logstage.elastic

import izumi.fundamentals.platform.language.CodePositionMaterializer
import izumi.logstage.api.Log.CustomContext
import izumi.logstage.api.Log.Level.Debug
import izumi.logstage.api.rendering.logunits.Styler.PadType
import izumi.logstage.api.rendering.logunits.{Extractor, Renderer, Styler}
import izumi.logstage.api.rendering.{RenderingOptions, StringRenderingPolicy}
import izumi.logstage.api.{IzLogger, Log}
import izumi.logstage.sink.slf4j.LogSinkLegacySlf4jImpl

trait Logging extends LoggerTags {
  protected def logContext: CustomContext = CustomContext()

  // used for event parameters & context prefixes
  protected lazy val loggerClass: String = this.getClass.getName.takeWhile(_ != '$')

  implicit lazy val log: IzLogger = {
    val legacyNameNormalisation = sys.props.get("logger.izumi.legacyNameNormalisation").contains("true")
    // set this to 0 to turn it off. the actual value will be shorter by the length of truncation suffix `[...]`
    val truncateStringValues = sys.props.get("logger.izumi.truncateStringValuesTo").map(_.toInt)
    val defaultTruncateStringValuesTo = 16 * 1024

    lazy val jsonPolicy = LogstageCirceElasticRenderingPolicy(
      loggerClass,
      legacyNameNormalisation,
      truncateStringValues.orElse(Some(defaultTruncateStringValuesTo)).filterNot(_ == 0),
    )
    lazy val stringPolicy = new StringRenderingPolicy(RenderingOptions.default, Some(Logging.template))

    val renderPolicies = sys.props.get("logger.izumi.sink") match {
      case Some("json") => Seq(jsonPolicy)
      case Some("json+string") => Seq(jsonPolicy, stringPolicy)
      case None => Seq(stringPolicy)
    }
    IzLogger(Debug, renderPolicies.map(new LogSinkLegacySlf4jImpl(_)))(logContext)
  }
}

trait LoggerTags {
  implicit class IzLoggerTags(log: IzLogger) {
    private[this] val empty = Log.Message.empty

    def traceT(tag: String, message: => Log.Message = empty)(implicit codePositionMaterializer: CodePositionMaterializer) =
      logT(Log.Level.Trace, tag, message)

    def debugT(tag: String, message: => Log.Message = empty)(implicit codePositionMaterializer: CodePositionMaterializer) =
      logT(Log.Level.Debug, tag, message)

    def infoT(tag: String, message: => Log.Message = empty)(implicit codePositionMaterializer: CodePositionMaterializer) =
      logT(Log.Level.Info, tag, message)

    def warnT(tag: String, message: => Log.Message = empty)(implicit codePositionMaterializer: CodePositionMaterializer) =
      logT(Log.Level.Warn, tag, message)

    def errorT(tag: String, message: => Log.Message = empty)(implicit codePositionMaterializer: CodePositionMaterializer) =
      logT(Log.Level.Error, tag, message)

    def critT(tag: String, message: => Log.Message = empty)(implicit codePositionMaterializer: CodePositionMaterializer) =
      logT(Log.Level.Crit, tag, message)

    def logT(
              logLevel: Log.Level, tag: String, message: => Log.Message
            )(
              implicit codePositionMaterializer: CodePositionMaterializer
            ) = {
      val maybePrefixedMessage = message match {
        case `empty` => empty
        case nonEmpty => Log.Message(" ") ++ nonEmpty
      }
      log.log(logLevel)(Log.Message(s"$tag") ++ maybePrefixedMessage)
    }
  }
}

object LoggerTags extends LoggerTags

object Logging extends LoggerTags {
  val template: Renderer.Aggregate = new Renderer.Aggregate(Seq(
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
