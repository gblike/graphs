package com.flowtick.graphs.graphml

import cats._
import cats.data.Validated._
import cats.data.{ NonEmptyList, Validated, ValidatedNel }
import cats.implicits._
import com.flowtick.graphs.{ Edge, Graph, Labeled }
import shapeless.HList

import scala.xml.{ Node, NodeBuffer, NodeSeq, Text }

private[graphml] case class PartialParsedGraph[N](
  id: Option[String],
  nodes: scala.collection.Map[String, GraphMLNode[N]],
  edgesXml: List[scala.xml.Node])

private[graphml] object PartialParsedGraph {
  def empty[N]: PartialParsedGraph[N] = PartialParsedGraph[N](None, Map.empty, List.empty)

  implicit def partialGraphSemiGroup[N]: Semigroup[PartialParsedGraph[N]] = new Semigroup[PartialParsedGraph[N]] {
    override def combine(x: PartialParsedGraph[N], y: PartialParsedGraph[N]): PartialParsedGraph[N] =
      PartialParsedGraph(None, x.nodes ++ y.nodes, x.edgesXml ++ y.edgesXml)
  }
}

class GraphMLDatatype[M, E, N](implicit
                               edgeLabel: Labeled[Edge[GraphMLEdge[E], GraphMLNode[N]], String],
                               nodeDataType: Datatype[GraphMLNode[N]],
                               edgeDataType: Datatype[GraphMLEdge[E]],
                               metaDataType: Datatype[GraphMLGraph[M]]) extends Datatype[GraphMLGraphType[M, E, N]] {

  override def serialize(g: GraphMLGraphType[M, E, N]): NodeSeq = {

    def graphKeys: Iterable[Node] = (metaDataType.keys ++ nodeDataType.keys ++ edgeDataType.keys ++ g.meta.keys).map { key: GraphMLKey =>
      // format: OFF
      <key id={ key.id }
           attr.name={ key.name.getOrElse(key.id) }
           for={ key.targetHint.map(Text(_)) }
           yfiles.type={ key.yfilesType.map(Text(_)) }
           attr.type={ key.typeHint.map(Text(_)) }
           graphs.type={key.graphsType.map(Text(_))} />
      // format: ON
    }

    def edgesXml: Iterable[Node] = g.edges.flatMap(edge => edgeDataType.serialize(edge.value))
    def nodesXml: Iterable[Node] = g.nodes.flatMap(nodeDataType.serialize)

    // format: OFF
    <graphml xmlns="http://graphml.graphdrawing.org/xmlns" xmlns:java="http://www.yworks.com/xml/yfiles-common/1.0/java" xmlns:sys="http://www.yworks.com/xml/yfiles-common/markup/primitives/2.0" xmlns:x="http://www.yworks.com/xml/yfiles-common/markup/2.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:y="http://www.yworks.com/xml/graphml" xmlns:yed="http://www.yworks.com/xml/yed/3" xsi:schemaLocation="http://graphml.graphdrawing.org/xmlns http://www.yworks.com/xml/schema/graphml/1.1/ygraphml.xsd">
      <!-- Created by https://github.com/flowtick/graphs GraphML renderer -->
      { graphKeys }
      <graph id="G" edgedefault="directed">
        { nodesXml }
        { edgesXml }
      </graph>
    </graphml>
    // format: ON
  }

  override def deserialize(
    from: NodeSeq,
    graphKeys: scala.collection.Map[String, GraphMLKey]): ValidatedNel[Throwable, GraphMLGraphType[M, E, N]] =
    from.headOption match {
      case Some(root) =>
        root
          .child
          .find(_.label == "graph")
          .map(parseGraphRoot(_, graphKeys))
          .getOrElse(invalidNel(new IllegalArgumentException(s"unable to find graph node in ${root.toString}")))

      case None => invalidNel(new IllegalArgumentException("cant parse empty xml"))
    }

  protected def parseGraphRoot(
    graph: Node,
    graphKeys: scala.collection.Map[String, GraphMLKey]): Validated[NonEmptyList[Throwable], GraphMLGraphType[M, E, N]] =
    metaDataType.deserialize(Seq(graph), graphKeys).andThen { meta =>
      parseGraphNodes(graph, graphKeys).andThen { parsedGraph =>
        parseEdges(parsedGraph.edgesXml, parsedGraph.nodes, graphKeys).andThen { edges =>
          valid(Graph(meta, edges, parsedGraph.nodes.values))
        }
      }
    }

  protected def parseEdges(
    edgeXmlNodes: List[scala.xml.Node],
    nodes: scala.collection.Map[String, GraphMLNode[N]],
    keys: scala.collection.Map[String, GraphMLKey]): Validated[NonEmptyList[Throwable], List[Edge[GraphMLEdge[E], GraphMLNode[N]]]] = {
    val edges = edgeXmlNodes.map { edgeNode =>
      val edge = edgeDataType.deserialize(NodeSeq.fromSeq(Seq(edgeNode)), keys).andThen { mlEdge =>
        (for {
          source <- GraphMLDatatype.singleAttributeValue("source", edgeNode)
          target <- GraphMLDatatype.singleAttributeValue("target", edgeNode)
          sourceNode <- nodes.get(source)
          targetNode <- nodes.get(target)
        } yield {
          validNel(Edge[GraphMLEdge[E], GraphMLNode[N]](mlEdge, sourceNode, targetNode))
        }).getOrElse(invalidNel(new IllegalArgumentException(s"unable to parse edge from ${edgeNode.toString}")))
      }
      edge
    }
    edges
  }.sequence

  protected def mergePartialGraphs(graphs: Seq[ValidatedNel[Throwable, PartialParsedGraph[N]]]): ValidatedNel[Throwable, PartialParsedGraph[N]] =
    graphs.foldLeft(validNel[Throwable, PartialParsedGraph[N]](PartialParsedGraph.empty[N]))(_ combine _)

  protected def parseNode(nodeXml: scala.xml.Node, graphKeys: scala.collection.Map[String, GraphMLKey]): Validated[NonEmptyList[Throwable], PartialParsedGraph[N]] = {
    nodeDataType.deserialize(Seq(nodeXml), graphKeys) match {
      case Valid(node) => mergePartialGraphs(nodeXml.child.iterator.map {
        case child if child.label == "graph" => parseGraphNodes(child, graphKeys)
        case _ => valid(PartialParsedGraph(None, Map(node.id -> node), List.empty))
      }.toSeq)

      case Invalid(error) => invalid(error)
    }
  }

  protected def parseGraphNodes(graphNode: scala.xml.Node, graphKeys: scala.collection.Map[String, GraphMLKey]): ValidatedNel[Throwable, PartialParsedGraph[N]] = {
    val id = GraphMLDatatype.singleAttributeValue("id", graphNode)

    val partialGraphs: Seq[Validated[NonEmptyList[Throwable], PartialParsedGraph[N]]] = graphNode.child.iterator.zipWithIndex.map {
      case (nodeXml: scala.xml.Node, _: Int) if nodeXml.label == "node" =>
        parseNode(nodeXml, graphKeys: scala.collection.Map[String, GraphMLKey])

      case (edge: scala.xml.Node, _: Int) if edge.label == "edge" =>
        valid(PartialParsedGraph[N](None, Map.empty, List(edge)))

      case _ => valid(PartialParsedGraph.empty[N])
    }.toSeq

    mergePartialGraphs(partialGraphs).map(_.copy(id = id))
  }

}

object GraphMLDatatype {

  def apply[V, N, M](implicit graphMLDatatype: Datatype[GraphMLGraphType[V, N, M]]): Datatype[GraphMLGraphType[V, N, M]] = graphMLDatatype

  protected[graphml] def singleAttributeValue(attributeName: String, node: scala.xml.Node): Option[String] = {
    node.attribute(attributeName).getOrElse(Seq.empty).headOption.map(_.text)
  }

  protected[graphml] def parseProperties(node: Node): Seq[GraphMLProperty] = {
    node.child.iterator.flatMap {
      case data if data.label == "data" =>
        val typeHint = GraphMLDatatype.singleAttributeValue("type", data).filter(_.nonEmpty)
        GraphMLDatatype.singleAttributeValue("key", data).map { keyId =>
          val value = if (data.child.exists(!_.isInstanceOf[Text])) data.child else data.child.text
          GraphMLProperty(keyId, value, typeHint)
        }
      case _ => None
    }.toSeq
  }

  protected[graphml] def parseKeys(rootElem: scala.xml.Node): scala.collection.Map[String, GraphMLKey] =
    rootElem.child.filter(_.label.toLowerCase == "key").flatMap { keyElem =>
      GraphMLDatatype.singleAttributeValue("id", keyElem).map(id => {
        (id, GraphMLKey(
          id = id,
          name = GraphMLDatatype.singleAttributeValue("attr.name", keyElem),
          targetHint = GraphMLDatatype.singleAttributeValue("for", keyElem),
          typeHint = GraphMLDatatype.singleAttributeValue("attr.type", keyElem),
          graphsType = GraphMLDatatype.singleAttributeValue("graphs.type", keyElem),
          yfilesType = GraphMLDatatype.singleAttributeValue("yfiles.type", keyElem)))
      })
    }.toMap

  protected def isValueProperty(
    property: GraphMLProperty,
    graphKeys: collection.Map[String, GraphMLKey],
    valueKeys: List[String]): Boolean = {
    graphKeys.get(property.key).exists(key => key.graphsType.isDefined || key.name.exists(keyName => valueKeys.contains(keyName)))
  }

  protected[graphml] def parseValue[T, Repr <: HList](
    xml: Node,
    graphKeys: collection.Map[String, GraphMLKey],
    valueKeys: List[String])(implicit fromList: FromList[T, Repr]): ValidatedNel[IllegalStateException, ValueWithProperties[T]] = {
    val properties = GraphMLDatatype.parseProperties(xml)

    val nonValueProps: Seq[GraphMLProperty] = properties.filterNot(isValueProperty(_, graphKeys, valueKeys))

    val valueList: Seq[Any] = properties.filter(isValueProperty(_, graphKeys, valueKeys)).map {
      case GraphMLProperty(_, value: NodeBuffer, _) => value.mkString("")
      case GraphMLProperty(_, value: Any, Some("string")) => value.toString
      case GraphMLProperty(_, value: Any, Some("integer")) => value.toString.toInt
      case GraphMLProperty(_, value: Any, Some("double")) => value.toString.toDouble
      case GraphMLProperty(_, value: Any, _) => value
    }.take(valueKeys.length)

    fromList(valueList)
      .map(value => validNel(ValueWithProperties[T](value, nonValueProps)))
      .getOrElse(invalidNel(new IllegalStateException(s"unable to parse value from properties: ${properties.toList.toString} (${xml.toString})")))
  }
}
