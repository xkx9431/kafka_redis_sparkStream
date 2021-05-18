package logsystem
import java.util.Properties
import org.apache.kafka.clients.consumer.{ConsumerConfig, ConsumerRecord}
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}
import org.apache.log4j.{Level, Logger}
import org.apache.spark.SparkConf
import org.apache.spark.streaming.dstream.InputDStream
import org.apache.spark.streaming.kafka010._
import org.apache.spark.streaming.{Seconds, StreamingContext}

object MsgTransfer {
  val log = Logger.getLogger(this.getClass)

  def main(args: Array[String]): Unit = {
    Logger.getLogger("org").setLevel(Level.ERROR)
    val conf = new SparkConf().setAppName(this.getClass.getCanonicalName).setMaster("local[*]")
    val ssc = new StreamingContext(conf, Seconds(5))

    // 需要消费的topic
    val topics: Array[String] = Array("mytopic1")
    val groupid = "mygroup1"
    // 定义kafka相关参数
    val kafkaParams: Map[String, Object] = getKafkaConsumerParameters(groupid)
    // 从Redis获取offset
    val fromOffsets = OffsetsWithRedisUtils.getOffsetsFromRedis(topics, groupid)

    // 创建DStream
    val dstream: InputDStream[ConsumerRecord[String, String]] = KafkaUtils.createDirectStream(
      ssc,
      LocationStrategies.PreferConsistent,
      // 从kafka中读取数据
      ConsumerStrategies.Subscribe[String, String](topics, kafkaParams, fromOffsets)
    )
    // 转换后的数据发送到另一个topic
    dstream.foreachRDD { rdd =>
      if (!rdd.isEmpty) {
        // 获取消费偏移量
        val offsetRanges: Array[OffsetRange] = rdd.asInstanceOf[HasOffsetRanges].offsetRanges
        // 处理数据发送到topic2
        rdd.foreachPartition(process)
        // 将offset保存到Redis
        OffsetsWithRedisUtils.saveOffsetsToRedis(offsetRanges, groupid)
      }
    }

    // 启动作业
    ssc.start()
    // 持续执行
    ssc.awaitTermination()
  }

  // 将处理后的数据发送到topic2
  def process(iter: Iterator[ConsumerRecord[String, String]]) = {
    iter.map(line => parse(line.value))
      .filter(!_.isEmpty)
      .foreach(line => sendMsg2Topic(line, "mytopic2"))
  }

  // 调用kafka生产者发送消息
  def sendMsg2Topic(msg: String, topic: String): Unit = {
    println(msg)
    val producer = new KafkaProducer[String, String](getKafkaProducerParameters())
    val record = new ProducerRecord[String, String](topic, msg)
    producer.send(record)
  }

  // 修改数据格式，将逗号分隔变成竖线分割
  def parse(text: String): String = {
    try {
      val arr = text.replace("<<<!>>>", "").split(",")
      if (arr.length != 15) return ""
      arr.mkString("|")
    } catch {
      case e: Exception =>
        log.error("解析数据出错！", e)
        ""
    }
  }

  // 定义kafka消费者的配置信息
  def getKafkaConsumerParameters(groupid: String): Map[String, Object] = {
    Map[String, Object](
      ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> "centos7-1:9092",
      ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG -> classOf[StringDeserializer],
      ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG -> classOf[StringDeserializer],
      ConsumerConfig.GROUP_ID_CONFIG -> groupid,
      ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG -> (false: java.lang.Boolean),
    )
  }

  // 定义生产者的kafka配置
  def getKafkaProducerParameters(): Properties = {
    val prop = new Properties()
    prop.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "centos7-1:9092")
    prop.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer])
    prop.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer])
    prop
  }

}
