package net.sc8s.logstage.elastic

import LogstageCirceElasticRenderingPolicy.{MaxLength, eventTagName}
import io.circe._
import io.circe.syntax._
import izumi.logstage.api.Log
import izumi.logstage.api.Log.LogArg
import izumi.logstage.api.rendering.json.LogstageCirceRenderingPolicy
import izumi.logstage.api.rendering.json.LogstageCirceRenderingPolicy.Format
import izumi.logstage.api.rendering.{RenderedParameter, RenderingOptions}

import scala.collection.mutable

/*
 There two differences compared to the original rendering policy:
  1. All parameters are scoped (aka prefixed) with the logging classname. This way parameter name clashes of different types are avoided which leads to dropped events in elastic due to mixing of mapping types for the same path.
  2. Context parameters are additionally added to EventKey with a typeSuffix (to avoid the above mentioned mapping issue) for easier filtering in ElasticSearch
  3. Names are NOT normalized as per https://www.elastic.co/guide/en/ecs/8.6/ecs-custom-fields-in-ecs.html#_capitalization
 */
class LogstageCirceElasticRenderingPolicy(
                                           loggerClass: String,
                                           legacyNameNormalisation: Boolean,
                                           truncateStringValues: Option[MaxLength],
                                         ) extends LogstageCirceRenderingPolicy(false) {

  override def render(entry: Log.Entry): String = {
    val result = mutable.ArrayBuffer[(String, Json)]()

    val formatted = Format.formatMessage(entry, RenderingOptions.colorless)
    val params = parametersToJson[RenderedParameter](
      formatted.parameters ++ formatted.unbalanced,
      // by default logstage normalizes the name by transforming camelCase to snake_case . I find this unnecessary and even annoying when e.g. prefixing fields with the full class name (which is camelCased). ECS even recommends using camelCase https://www.elastic.co/guide/en/ecs/8.6/ecs-custom-fields-in-ecs.html#_capitalization
      parameter =>
        if (legacyNameNormalisation) parameter.normalizedName
        else parameter.arg.name,
      repr,
    )

    val ctx = parametersToJson[LogArg](
      entry.context.customContext.values,
      _.name,
      v => repr(Format.formatArg(v, withColors = false)),
    )

    if (params.nonEmpty) {
      val paramsWithoutTag = params - eventTagName

      val paramsWithoutTagWithTypeSuffix = addTypeSuffix(paramsWithoutTag).asJsonObject

      // custom wrapper scoping parameters using tag and loggerClass
      val wrapped =
        if (paramsWithoutTag.isEmpty) params.asJson
        else {
          val wrappedEvent = params
            .get(eventTagName)
            .flatMap(_.asString)
            .fold(paramsWithoutTag.asJsonObject)(tag => JsonObject(
              eventTagName -> tag.asJson,
              tag -> paramsWithoutTag.asJsonObject.asJson
            ))
          val jsonObject = {
            // add context fields to EventKey to allow a single filter spanning context & message. message fields take precedence (but all context fields are still in ContextKey)
            addTypeSuffix(ctx)
              .asJsonObject
              .deepMerge(
                paramsWithoutTagWithTypeSuffix.add(loggerClass, wrappedEvent.asJson)
              )
          }
          params.get(eventTagName).fold(jsonObject)(eventTag => jsonObject.add(eventTagName, eventTag)).asJson
        }

      result += EventKey -> wrapped
    }

    if (ctx.nonEmpty) {
      // scope context by loggerClass (unscoped and with type suffix is already inside EventKey)
      result += ContextKey -> JsonObject(loggerClass -> ctx.asJson).asJson
    }

    result ++= makeEventEnvelope(entry, formatted)

    val json = Json.fromFields(result)
    val maybeTruncatedJson = truncateStringValues.fold(json)(truncateStringValues(json, _))

    dump(maybeTruncatedJson)
  }

  private val truncateIndicationSuffix = "[...]"

  private def truncateStringValues(json: Json, maxLength: Int): Json = {
    val truncateMaxLengthWithoutSuffix = maxLength - truncateIndicationSuffix.length
    // this is more efficient than json.fold as it avoids unnecessary un- and re-wrapping
    json
      .mapObject(_.mapValues(truncateStringValues(_, maxLength)))
      .mapArray(_.map(truncateStringValues(_, maxLength)))
      .mapString(string => if (string.length > maxLength) string.take(Math.max(truncateMaxLengthWithoutSuffix, 0)) + truncateIndicationSuffix else string)
  }

  // allows indexing of fields with a common name but different types (which elastic rejects)
  private def addTypeSuffix(params: Map[String, Json]) =
    params.map { case (name, value) =>
      val typeSuffix = value.fold(
        "",
        _ => "b",
        _ => "n",
        _ => "s",
        _ => "a",
        _ => "o",
      ).toUpperCase
      s"${name}_$typeSuffix" -> value
    }
}

object LogstageCirceElasticRenderingPolicy {
  @inline def apply(loggerClass: String, legacyNameNormalisation: Boolean = false, truncateStringValues: Option[MaxLength] = None): LogstageCirceElasticRenderingPolicy =
    new LogstageCirceElasticRenderingPolicy(loggerClass, legacyNameNormalisation, truncateStringValues)

  val eventTagName = "tag"

  type MaxLength = Int
}