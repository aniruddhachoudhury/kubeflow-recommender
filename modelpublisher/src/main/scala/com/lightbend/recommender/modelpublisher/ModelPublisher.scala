package com.lightbend.recommender.modelpublisher

import java.io.{ByteArrayOutputStream, InputStream}
import java.text.SimpleDateFormat
import java.util.Date

import com.lightbend.model.modeldescriptor.ModelDescriptor
import com.lightbend.recommender.ModelServingConfiguration._
import com.lightbend.recommender.client.MessageSender
import io.minio.MinioClient
import scalaj.http._

import scala.io.Source
import scala.collection.JavaConverters._

object ModelPublisher {

  val default_directory = "recommender"
  val df = new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss")

  def main(args: Array[String]): Unit = {

    println(s"ModelPublisher. Minio: url - $MINIO_URL, key - $MINIO_KEY, secret - $MINIO_SECRET. Kafka - $KAFKA_BROKER")

    // Create a minioClient with the MinIO Server URL, Access key and Secret key.
    val minioClient = new MinioClient(MINIO_URL, MINIO_KEY, MINIO_SECRET)

    // Get current directory
    var directory = ""
    var stream : InputStream = null
    try{
      stream = minioClient.getObject("data", "recommender/directory.txt")
      directory = Source.fromInputStream(stream).mkString
      stream.close
    } catch {case _: Throwable => }

    // Calculate URL
    val url = if(directory == default_directory) DEFAULT_URL else ALTERNATIVE_URL

    // Get Model files
    val models = getmodelFiles(minioClient, "models", s"$directory/1")
    println(models)

    // Make sure we can access endpoint
    var resp = 300
    var attempts = 0
    var tmouot = 500
    while((resp != 200) && (attempts < 20)) {
      try {Thread.sleep(tmouot)} catch {
        case _: Throwable => // Ignore
      }
      val response: HttpResponse[String] = Http(s"$url/v1/models/$directory/versions/1").asString
      resp = response.code
      attempts = attempts + 1
      tmouot = tmouot * 2
    }

    // Publish modle
    if(resp == 200){
      val sender = MessageSender(KAFKA_BROKER)
      val bos = new ByteArrayOutputStream()
      val cdate = df.format(new Date())
      val model = new ModelDescriptor(s"Recommender $cdate", s"generated at $cdate",
        s"$url/v1/models/$directory/versions/1:predict")
      model.writeTo(bos)
      sender.writeValue(MODELS_TOPIC, bos.toByteArray)

      println(s"Published Model ${model.description}")

    }
  }

  private def getmodelFiles(client: MinioClient, bucket: String, prefix: String): Seq[String] = {
    client.listObjects(bucket,prefix).asScala.map(_.get().objectName()).toSeq
  }
}