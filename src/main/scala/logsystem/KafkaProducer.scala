package logsystem

import java.util.Properties

import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.log4j.{Level, Logger}
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}

object KafkaProducer {

  def main(args: Array[String]): Unit = {
    Logger.getLogger("org").setLevel(Level.ERROR)
    val conf = new SparkConf().setAppName(this.getClass.getCanonicalName.init).setMaster("local[*]")
    val sc = new SparkContext(conf)

    // 读取sample.log文件数据
    val lines: RDD[String] = sc.textFile("data/sample.log")

    // 定义 kafka producer参数
    val prop = new Properties()
    // kafka的访问地址
    prop.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "centos7-1:9092")
    // key和value的序列化方式
    prop.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer])
    prop.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer])

    // 将读取到的数据发送到mytopic1
    lines.foreachPartition{iter =>
      // 初始化KafkaProducer
      val producer = new KafkaProducer[String, String](prop)
      iter.foreach{line =>
        // 封装数据
        val record = new ProducerRecord[String, String]("mytopic1", line)
        // 发送数据
        producer.send(record)
      }
      producer.close()
    }
  }
}
