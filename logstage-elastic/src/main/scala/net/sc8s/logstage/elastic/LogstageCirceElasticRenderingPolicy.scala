package net.sc8s.logstage.elastic

import io.circe._
import io.circe.syntax._
import izumi.logstage.api.Log
import izumi.logstage.api.Log.LogArg
import izumi.logstage.api.rendering.{RenderedParameter, RenderingOptions}
import izumi.logstage.api.rendering.json.LogstageCirceRenderingPolicy
import izumi.logstage.api.rendering.json.LogstageCirceRenderingPolicy.Format
import net.sc8s.logstage.elastic.LogstageCirceElasticRenderingPolicy.eventTagName

import scala.collection.mutable

/*
 The only difference to the original rendering policy is that all parameters (except for `tag`) are scoped (aka prefixed) with the logging classname. This way parameter name clashes of different types are avoided which leads to dropped events in elastic due to mixing of mapping types for the same path.
 */
class LogstageCirceElasticRenderingPolicy(
                                           loggerClass: String,
                                           prettyPrint: Boolean = false
                                         ) extends LogstageCirceRenderingPolicy(prettyPrint) {

  override def render(entry: Log.Entry): String = {
    val result = mutable.ArrayBuffer[(String, Json)]()

    val formatted = Format.formatMessage(entry, RenderingOptions(withExceptions = true, colored = false))
    val params = parametersToJson[RenderedParameter](
      formatted.parameters ++ formatted.unbalanced,
      _.normalizedName,
      repr,
    )

    val ctx = parametersToJson[LogArg](
      entry.context.customContext.values,
      _.name,
      v => repr(Format.formatArg(v, withColors = false)),
    )

    if (params.nonEmpty) {
      val paramsWithoutTag = params - eventTagName

      // custom wrapper
      val wrapped =
        if (paramsWithoutTag.isEmpty) params.asJson
        else {
          val wrappedEvent = params
            .get(eventTagName)
            .flatMap(_.asString)
            .fold(paramsWithoutTag.asJsonObject)(tag => JsonObject(tag -> paramsWithoutTag.asJsonObject.asJson))
          val jsonObject = JsonObject(loggerClass -> wrappedEvent.asJson)
          params.get(eventTagName).fold(jsonObject)(eventTag => jsonObject.add(eventTagName, eventTag)).asJson
        }

      result += EventKey -> wrapped
    }

    if (ctx.nonEmpty) {
      // if you want to scope context
      //result += ContextKey -> JsonObject(loggerClass -> ctx.asJson).asJson
      result += ContextKey -> ctx.asJson
    }

    result ++= makeEventEnvelope(entry, formatted)

    val json = Json.fromFields(result)

    dump(json)
  }
}

object LogstageCirceElasticRenderingPolicy {
  @inline def apply(loggerClass: String, prettyPrint: Boolean = false): LogstageCirceElasticRenderingPolicy = new LogstageCirceElasticRenderingPolicy(loggerClass, prettyPrint)

  val eventTagName = "tag"
}