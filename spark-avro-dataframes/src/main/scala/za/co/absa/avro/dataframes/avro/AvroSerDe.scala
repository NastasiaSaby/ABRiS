/*
 * Copyright 2018 Barclays Africa Group Limited
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

package za.co.absa.avro.dataframes.avro

import scala.reflect.ClassTag

import org.apache.avro.Schema
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.BinaryDecoder
import org.apache.avro.io.DecoderFactory
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Encoders
import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.encoders.RowEncoder
import org.apache.spark.sql.streaming.DataStreamReader

import za.co.absa.avro.dataframes.avro.format.SparkAvroConversions
import za.co.absa.avro.dataframes.avro.parsing.AvroToSparkParser
import za.co.absa.avro.dataframes.avro.parsing.utils.AvroSchemaUtils
import za.co.absa.avro.dataframes.avro.read.ScalaDatumReader
import java.security.InvalidParameterException
import org.apache.spark.sql.util.SchemaUtils

/**
 * This object provides the main point of integration between applications and this library.
 */
object AvroSerDe {

  private val avroParser = new AvroToSparkParser()
  private var reader: ScalaDatumReader[GenericRecord] = _  

  /**
   * Method responsible for receiving binary Avro records and converting them into Spark Rows.
   */
  private def decodeAvro[T](avroRecord: Array[Byte])(implicit tag: ClassTag[T]): Row = {
    val decoder = DecoderFactory.get().binaryDecoder(avroRecord, null)
    val decodedAvroData: GenericRecord = reader.read(null, decoder)

    avroParser.parse(decodedAvroData)
  }

  private def createAvroReader(schemaPath: String) = {
    reader = new ScalaDatumReader[GenericRecord](AvroSchemaUtils.load(schemaPath))
  }

  private def createRowEncoder(schema: Schema) = {
    RowEncoder(SparkAvroConversions.toSqlType(schema))
  }

  /**
   * This class provides the method that performs the Kafka/Avro/Spark connection.
   *
   * It loads binary data from a stream and feed them into an Avro/Spark decoder, returning the resulting rows.
   *
   * It requires the path to the Avro schema which defines the records to be read.
   */
  implicit class Deserializer(dsReader: DataStreamReader) extends Serializable {
    
    def avro(schemaPath: String) = {
      implicit val rowEncoder = createRowEncoder(AvroSchemaUtils.load(schemaPath))
      
      dsReader.load.select("value")
      .as(Encoders.BINARY)
      .mapPartitions(partition => {
        createAvroReader(schemaPath)          
        partition.map(avroRecord => {
          decodeAvro(avroRecord)
        })
      })
    }
  }
  
  /**
   * This class provides methods to perform the translation from Dataframe Rows into Avro records on the fly. 
   * 
   * Users can either, inform the path to the destination Avro schema or inform record name and namespace and the schema
   * will be inferred from the Dataframe.
   * 
   * The methods are "storage-agnostic", which means the provide Dataframes of Avro records which can be stored into any
   * sink (e.g. Kafka, Parquet, etc).
   */
  implicit class Serializer(dataframe: Dataset[Row]) {
    
    /**
     * Converts from Dataset[Row] into Dataset[Array[Byte]] containing Avro records. 
     * 
     * It is important to keep in mind that the specification for a field in the schema MUST be the same at both ends, writer and reader.
     * For some fields (e.g. strings), Spark can ignore the nullability specified in the SQL struct (SPARK-14139). This issue could lead 
     * to fields being ignored. Thus, it is important to check the final SQL schema after Spark has created the Dataframes. 
     * 
     * For instance, the Spark construct 'StructType("name", StringType, false)' translates to the Avro field {"name": "name", "type":"string"}. 
     * However, if Spark changes the nullability (StructType("name", StringType, TRUE)), the Avro field becomes a union: {"name":"name", "type": ["string", "null"]}.
     * 
     * The difference in the specifications will prevent the field from being correctly loaded by Avro readers, leading to data loss.
     */
    def avro(schemaPath: String): Dataset[Array[Byte]] = {            
      val plainAvroSchema = AvroSchemaUtils.loadPlain(schemaPath)            
      toAvro(dataframe, plainAvroSchema)
    }
    
    /**
     * Converts from Dataset[Row] into Dataset[Array[Byte]] containing Avro records.
     * 
     * The API will infer the Avro schema from the incoming Dataframe. The inferred schema will receive the name and namespace informed as parameters.
     * 
     * The API will throw in case the Dataframe does not hava a schema.
     * 
     * Differently than the other API, this one does not suffer from the schema changing issue, since the final Avro schema will be derived from the schema
     * already used by Spark. 
     */
    def avro(schemaName: String, schemaNamespace: String): Dataset[Array[Byte]] = {      
      if (dataframe.schema == null || dataframe.schema.isEmpty) {
        throw new InvalidParameterException("Dataframe does not have a schema.")
      }
      
      val plainAvroSchema = SparkAvroConversions.toAvroSchema(dataframe.schema, schemaName, schemaNamespace).toString()                     
      toAvro(dataframe, plainAvroSchema)
    }   
    
    /**
     * Converts a Dataset[Row] into a Dataset[Array[Byte]] containing Avro schemas generated according to the plain specification informed as a parameter.
     */
    private def toAvro(rows: Dataset[Row], plainAvroSchema: String) = {
      implicit val recEncoder = Encoders.BINARY
      rows.mapPartitions(partition => {        
        val avroSchema = AvroSchemaUtils.parse(plainAvroSchema)
        val sparkSchema = SparkAvroConversions.toSqlType(avroSchema)
        partition.map(row => SparkAvroConversions.rowToBinaryAvro(row, sparkSchema, avroSchema))        
      })      
    }
  }    
}