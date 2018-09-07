package com.raphtory.core.actors.partitionmanager

import ch.qos.logback.classic.Level
import com.mongodb.casbah.Imports.{$addToSet, MongoDBList, MongoDBObject}
import com.mongodb.casbah.MongoConnection
import com.raphtory.core.model.graphentities.{Edge, Entity, Property, Vertex}
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.collection.parallel.mutable.ParTrieMap
import ch.qos.logback.classic.Level
import com.mongodb.casbah.Imports.{$addToSet, _}
import com.mongodb.casbah.MongoConnection
import net.liftweb.json._

import scala.collection.parallel.ParSet
object MongoFactory {

  private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[ch.qos.logback.classic.Logger]
  root.setLevel(Level.ERROR)
  implicit val formats = DefaultFormats // Brings in default date formats etc.

  private val DATABASE = "raphtory"
  val connection = MongoConnection()
  val edges = connection(DATABASE)("edges")
  val vertices = connection(DATABASE)("vertices")

  private var vertexOperator = MongoFactory.vertices.initializeOrderedBulkOperation
  private var edgeOperator = MongoFactory.edges.initializeOrderedBulkOperation
  def flushBatch() ={
    try {
      vertexOperator.execute()
      edgeOperator.execute()
      println("flushing")
    }
    catch {
      case e:Exception => //e.printStackTrace()
    }
    vertexOperator = MongoFactory.vertices.initializeOrderedBulkOperation
    edgeOperator = MongoFactory.edges.initializeOrderedBulkOperation
  }

  def vertex2Mongo(entity:Vertex,cutoff:Long)={
    if(entity beenSaved()){
      updateVertex(entity,cutoff)
    }
    else{
      newVertex(entity,cutoff)
    }
  }
  def edge2Mongo(entity:Edge,cutoff:Long)={
    if(entity beenSaved()){
      updateEdge(entity,cutoff)
    }
    else{
      newEdge(entity,cutoff)
    }
  }

  private def newVertex(vertex:Vertex,cutOff:Long):Unit ={
    val history = vertex.compressAndReturnOldHistory(cutOff)
    if(history isEmpty)
      return
    val builder = MongoDBObject.newBuilder
    builder += "_id" -> vertex.getId
    builder += "oldestPoint" -> vertex.oldestPoint.get.toDouble
    builder += "history" -> convertHistory(history)
    builder += "properties" -> convertProperties(vertex.properties,cutOff)
    val set = vertex.getNewAssociatedEdges()
    if (set.nonEmpty)
      builder += "associatedEdges" -> convertAssociatedEdges(set)
    //convertProperties(builder,entity.properties,cutOff) // no outside properties list
    vertexOperator.insert(builder.result())
  }

   private def updateVertex(entity: Entity,cutOff:Long) ={
    val history = convertHistoryUpdate(entity.compressAndReturnOldHistory(cutOff))
    val dbEntity = vertexOperator.find(MongoDBObject("_id" -> entity.getId))
    dbEntity.updateOne($addToSet("history") $each(history:_*))

    for((key,property) <- entity.properties){
      val entityHistory = convertHistoryUpdate(property.compressAndReturnOldHistory(cutOff))
      if(history.nonEmpty)
        dbEntity.updateOne($addToSet(s"properties.$key") $each(entityHistory:_*)) //s"properties.$key"
    }
  }

  def convertAssociatedEdges(set:ParSet[Edge]):MongoDBList = {
    val builder = MongoDBList.newBuilder
    for(edge <- set){
      println(edge.getId)
      builder += edge.getId.toDouble
    }
    builder.result()
  }

  private def newEdge(entity:Entity,cutOff:Long):Unit ={
    val history = entity.compressAndReturnOldHistory(cutOff)
    if(history isEmpty)
      return
    val builder = MongoDBObject.newBuilder
    builder += "_id" -> entity.getId
    builder += "oldestPoint" -> entity.oldestPoint.get.toDouble
    builder += "history" -> convertHistory(history)
    builder += "properties" -> convertProperties(entity.properties,cutOff)
    //convertProperties(builder,entity.properties,cutOff) // no outside properties list
    edgeOperator.insert(builder.result())
  }

   private def updateEdge(entity: Entity,cutOff:Long) ={
    val history = convertHistoryUpdate(entity.compressAndReturnOldHistory(cutOff))
    val dbEntity = edgeOperator.find(MongoDBObject("_id" -> entity.getId))
    dbEntity.updateOne($addToSet("history") $each(history:_*))

    for((key,property) <- entity.properties){
      val entityHistory = convertHistoryUpdate(property.compressAndReturnOldHistory(cutOff))
      if(history.nonEmpty)
        dbEntity.updateOne($addToSet(s"properties.$key") $each(entityHistory:_*)) //s"properties.$key"
    }

  }


  private def convertHistory[b <: Any](history:mutable.TreeMap[Long,b]):MongoDBList ={
    val builder = MongoDBList.newBuilder
    for((k,v) <-history)
      if(v.isInstanceOf[String])
        builder += MongoDBObject("time"->k.toDouble,"value"->v.asInstanceOf[String])
      else
        builder += MongoDBObject("time"->k.toDouble,"value"->v.asInstanceOf[Boolean])
    builder.result
  }
  //these are different as the update requires a scala list which can be 'eached'
  private def convertHistoryUpdate[b <: Any](history:mutable.TreeMap[Long,b]):List[MongoDBObject] ={
    val builder = mutable.ListBuffer[MongoDBObject]()
    for((k,v) <-history)
      if(v.isInstanceOf[String])
        builder += MongoDBObject("time"->k.toDouble,"value"->v.asInstanceOf[String])
      else
        builder += MongoDBObject("time"->k.toDouble,"value"->v.asInstanceOf[Boolean])

    builder.toList
  }

  private def convertProperties(properties: ParTrieMap[String,Property],cutOff:Long):MongoDBObject = {
      val builder = MongoDBObject.newBuilder
      for ((k, v) <- properties) {
        builder += k -> convertHistory(v.compressAndReturnOldHistory(cutOff))
      }
      builder.result
  }

  def retrieveVertexHistory(id:Long):SavedHistory = {
    parse(vertices.findOne(MongoDBObject("_id" -> id),MongoDBObject("_id"->0,"history" -> 1)).getOrElse("").toString).extract[SavedHistory]
  }

  def retrieveVertexPropertyHistory(id:Long,key:String):SavedProperty ={
    //println(vertices.findOne(("_id" $eq id),MongoDBObject("_id"->0,s"properties.$key"->1)).getOrElse(""))
    //println(vertices.findOne(MongoDBObject("_id" -> id,"properties" -> "properties" $elemMatch(MongoDBObject("name" -> "key"))),MongoDBObject("_id"->0,s"properties.$key" -> 1)).getOrElse(""))
    val json = vertices.findOne(MongoDBObject("_id" -> id),MongoDBObject("_id"->0,s"properties.$key" -> 1)).getOrElse("").toString
    parse(json.substring(17,json.length-1).replaceFirst(key,"property")).extract[SavedProperty]
  }
  def retrieveVertex(id:Long):SavedVertex ={
    parse(vertices.findOne(MongoDBObject("_id" -> id),MongoDBObject("_id"->0)).getOrElse("").toString).extract[SavedVertex]
  }

}
case class SavedVertex(history:List[HistoryPoint],properties:Map[String,List[PropertyPoint]],oldestPoint:Double,associatedEdges:List[Double])
case class SavedHistory(history:List[HistoryPoint])
case class SavedProperties(properties:Map[String,List[PropertyPoint]])
case class SavedProperty(property:List[PropertyPoint])
case class PropertyPoint(time:Double, value: String)
case class HistoryPoint(time:Double,value:Boolean)
