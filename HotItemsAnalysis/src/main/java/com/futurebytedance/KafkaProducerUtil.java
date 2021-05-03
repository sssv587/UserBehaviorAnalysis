package com.futurebytedance;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Properties;

/**
 * @author yuhang.sun
 * @version 1.0
 * @date 2021/5/3 - 14:23
 * @Description 批量写入kafka topic的脚本
 */
public class KafkaProducerUtil {
    public static void main(String[] args) throws Exception {
        writeToKafka("hotitems");
    }

    // 包装一个写入kafka的方法
    public static void writeToKafka(String topic) throws Exception {
        // kafka配置
        Properties properties = new Properties();
        properties.setProperty("bootstrap.servers", "localhost:9092");
        properties.setProperty("key.deserializer",
                "org.apache.kafka.common.serialization.StringSerializer");
        properties.setProperty("value.deserializer",
                "org.apache.kafka.common.serialization.StringSerializer");

        // 定义一个kafka Producer
        KafkaProducer<String, String> kafkaProducer = new KafkaProducer<>(properties);

        // 用缓冲方式读取文本
        BufferedReader bufferedReader = new BufferedReader(new FileReader("C:\\Users\\10926\\IdeaProjects\\UserBehaviorAnalysis\\HotItemsAnalysis\\src\\main\\resources\\UserBehavior.csv"));
        String line;
        while ((line = bufferedReader.readLine()) != null) {
            ProducerRecord<String, String> producerRecord = new ProducerRecord<>(topic, line);
            // 用Producer发送数据
            kafkaProducer.send(producerRecord);
        }
        kafkaProducer.close();
    }
}
