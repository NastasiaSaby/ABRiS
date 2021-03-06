/*
 * Copyright 2018 ABSA Group Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package za.co.absa.abris.examples

import java.io.FileInputStream
import java.util.Properties

import org.apache.spark.sql.{Dataset, Encoder, Row, SparkSession}
import org.apache.spark.sql.catalyst.encoders.RowEncoder
import za.co.absa.abris.avro.format.SparkAvroConversions
import za.co.absa.abris.avro.parsing.utils.AvroSchemaUtils
import za.co.absa.abris.examples.data.generation.ComplexRecordsGenerator
import za.co.absa.abris.examples.utils.ExamplesUtils

import scala.collection.JavaConversions._

object KafkaAvroWriter {

  private val PARAM_JOB_NAME = "job.name"
  private val PARAM_JOB_MASTER = "job.master"
  private val PARAM_KEY_AVRO_SCHEMA = "key.avro.schema"
  private val PARAM_PAYLOAD_AVRO_SCHEMA = "payload.avro.schema"
  private val PARAM_AVRO_RECORD_NAME = "avro.record.name"
  private val PARAM_AVRO_RECORD_NAMESPACE = "avro.record.namespace"
  private val PARAM_INFER_SCHEMA = "infer.schema"
  private val PARAM_LOG_LEVEL = "log.level"
  private val PARAM_TEST_DATA_ENTRIES = "test.data.entries"
  private val PARAM_EXECUTION_REPEAT = "execution.repeat"
  private val PARAM_NUM_PARTITIONS = "num.partitions"

  def main(args: Array[String]): Unit = {

    // there is a sample properties file at /src/test/resources/DataframeWritingExample.properties
    if (args.length != 1) {
      println("No properties file specified.")
      System.exit(1)
    }

    println("Loading properties from: " + args(0))
    val properties = loadProperties(args(0))
    
    for (key <- properties.keysIterator) {
      println(s"\t${key} = ${properties.getProperty(key)}")
    }
    
    val spark = SparkSession
      .builder()
      .appName(properties.getProperty(PARAM_JOB_NAME))
      .master(properties.getProperty(PARAM_JOB_MASTER))   
      .getOrCreate()

    spark.sparkContext.setLogLevel(properties.getProperty(PARAM_LOG_LEVEL))

    import ExamplesUtils._
    import spark.implicits._
    
    implicit val encoder = getEncoder()
    
    do {
      val rows = getRows(properties.getProperty(PARAM_TEST_DATA_ENTRIES).trim().toInt)
      val dataframe = spark.sparkContext.parallelize(rows, properties.getProperty(PARAM_NUM_PARTITIONS).toInt).toDF()

      dataframe.show()

      toAvro(dataframe, properties)
        .write       
        .format("kafka")
        .addOptions(properties) // 1. this method will add the properties starting with "option."; 2. security options can be set in the properties file
        .save()
    } while (properties.getProperty(PARAM_EXECUTION_REPEAT).toBoolean)
  }

  private def toAvro(dataframe: Dataset[Row], properties: Properties) = {
    import za.co.absa.abris.avro.AvroSerDe._
    if (properties.getProperty(PARAM_INFER_SCHEMA).trim().toBoolean) {
      dataframe.toAvro(properties.getProperty(PARAM_AVRO_RECORD_NAME), properties.getProperty(PARAM_AVRO_RECORD_NAMESPACE))
    } else {
      dataframe.toAvro(properties.getProperty(PARAM_PAYLOAD_AVRO_SCHEMA))
    }
  }

  private def loadProperties(path: String): Properties = {
    val properties = new Properties()
    properties.load(new FileInputStream(path))
    properties
  }

  private def getRows(howMany: Int): List[Row] = {
    ComplexRecordsGenerator.generateUnparsedRows(howMany)
  }

  private def getEncoder(): Encoder[Row] = {
    val avroSchema = AvroSchemaUtils.parse(ComplexRecordsGenerator.usedAvroSchema)
    val sparkSchema = SparkAvroConversions.toSqlType(avroSchema)
    RowEncoder.apply(sparkSchema)
  }
}