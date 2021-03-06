package com.flowtick.graphs.editor

import cats.data.Validated.{Valid, _}
import cats.data.ValidatedNel
import com.flowtick.graphs.Graph
import com.flowtick.graphs.graphml.{Datatype, GraphMLKey}
import com.flowtick.graphs.json.schema.Schema
import com.flowtick.graphs.style._
import io.circe.Json

import scala.xml.NodeSeq

final case class EditorModel(graph: Graph[EditorGraphEdge, EditorGraphNode],
                             selection: Set[ElementRef] = Set.empty,
                             connectSelection: Boolean = false,
                             schema: EditorSchemaLike,
                             palette: EditorPaletteLike,
                             layout: EditorGraphLayoutLike,
                             styleSheet: StyleSheetLike,
                             version: Long = 0,
                             config: EditorConfiguration) {
  def updateGraph(updated: Graph[EditorGraphEdge, EditorGraphNode] => Graph[EditorGraphEdge, EditorGraphNode]): EditorModel =
    copy(graph = updated(graph))

  def updateLayout(updated: EditorGraphLayoutLike => EditorGraphLayoutLike): EditorModel =
    copy(layout = updated(layout))

  def updateStyleSheet(updated: StyleSheetLike => StyleSheetLike): EditorModel =
    copy(styleSheet = updated(styleSheet))

  def updateSchema(updated: EditorSchemaLike => EditorSchemaLike): EditorModel =
    copy(schema = updated(schema))

  override def toString: String = {
    s"""version = $version, connect = $connectSelection, selection = $selection"""
  }
}

final case class EditorSchemaHints(copyToLabel: Option[Boolean] = None,
                                   hideLabelProperty: Option[Boolean] = None,
                                   highlight: Option[String] = None,
                                   showJsonProperty: Option[Boolean] = None)

object EditorModel {
  def fromConfig(config: EditorConfiguration): EditorModel =
    EditorModel(
      graph = Graph.empty,
      schema = EditorSchemas(config.palettes.getOrElse(List.empty).map(_.schema)),
      layout = EditorGraphLayout(),
      styleSheet = StyleSheets(config.palettes.getOrElse(List.empty).map(_.styleSheet)),
      palette = EditorPalettes(config.palettes.getOrElse(List.empty)),
      config = config
    )

  type EditorSchema = Schema[EditorSchemaHints]

  def jsonDataType(emptyOnError: Boolean)(implicit stringDatatype: Datatype[String]): Datatype[Json] = new Datatype[Json] {
    override def serialize(value: Json, targetHint: Option[String]): NodeSeq = stringDatatype.serialize(value.noSpaces, targetHint)
    override def deserialize(from: NodeSeq,
                             graphKeys: collection.Map[String, GraphMLKey],
                             targetHint: Option[String]): ValidatedNel[Throwable, Json] =
      stringDatatype
        .deserialize(from, graphKeys, targetHint)
        .map(io.circe.parser.decode[Json]) match {
          case Valid(Right(json)) => valid(json)
          case Valid(Left(error)) => if (emptyOnError) validNel(Json.obj()) else invalidNel(new IllegalArgumentException("could not parse json", error))
          case Invalid(errors) => if (emptyOnError) validNel(Json.obj()) else invalid(errors.prepend(new IllegalArgumentException("unable to parse xml node")))
        }

    override def keys(targetHint: Option[String]): Seq[GraphMLKey] = stringDatatype.keys(targetHint)
  }
}
