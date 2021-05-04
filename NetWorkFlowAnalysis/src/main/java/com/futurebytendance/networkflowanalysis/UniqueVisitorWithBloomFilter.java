package com.futurebytendance.networkflowanalysis;

import com.futurebytendance.networkflowanalysis.bean.PageViewCount;
import com.futurebytendance.networkflowanalysis.bean.UserBehavior;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.timestamps.AscendingTimestampExtractor;
import org.apache.flink.streaming.api.functions.windowing.ProcessAllWindowFunction;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.triggers.Trigger;
import org.apache.flink.streaming.api.windowing.triggers.TriggerResult;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import redis.clients.jedis.Jedis;

import java.net.URL;

/**
 * @author yuhang.sun
 * @version 1.0
 * @date 2021/5/4 - 15:24
 * @Description 布隆过滤器实现uv去重
 */
public class UniqueVisitorWithBloomFilter {
    public static void main(String[] args) throws Exception {
        // 1.创建执行环境
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);

        // 2.读取数据，创建DataStream
        URL resource = HotPages.class.getResource("/UserBehavior.csv");
        DataStreamSource<String> inputStream = env.readTextFile(resource.getPath());


        // 3.转换为POJO，分配时间戳和watermark
        DataStream<UserBehavior> dataStream = inputStream
                .map((MapFunction<String, UserBehavior>) value -> {
                    String[] fields = value.split(",");
                    return new UserBehavior(new Long(fields[0]), new Long(fields[1]), new Integer(fields[2]), fields[3], new Long(fields[4]));
                }).assignTimestampsAndWatermarks(new AscendingTimestampExtractor<UserBehavior>() {
                    @Override
                    public long extractAscendingTimestamp(UserBehavior element) {
                        return element.getTimestamp() * 1000L;
                    }
                });

        // 开窗统计uv值
        SingleOutputStreamOperator<PageViewCount> uvStream = dataStream
                .filter(data -> "pv".equals(data.getBehavior()))
                .timeWindowAll(Time.hours(1))
                .trigger(new MyTrigger())
                .process(new UvCountResultWithBloomFilter());

        uvStream.print();

        env.execute("uv count with bloom filter job");
    }

    public static class MyTrigger extends Trigger<UserBehavior, TimeWindow> {

        @Override
        public TriggerResult onElement(UserBehavior element, long timestamp, TimeWindow window, TriggerContext ctx) throws Exception {
            // 每一条数据来到，就触发窗口计算，并且直接清空窗口
            return TriggerResult.FIRE_AND_PURGE;
        }

        @Override
        public TriggerResult onProcessingTime(long time, TimeWindow window, TriggerContext ctx) {
            return TriggerResult.CONTINUE;
        }

        @Override
        public TriggerResult onEventTime(long time, TimeWindow window, TriggerContext ctx) {
            return TriggerResult.CONTINUE;
        }

        @Override
        public void clear(TimeWindow window, TriggerContext ctx) throws Exception {

        }
    }

    // 自定义一个布隆过滤器
    public static class MyBloomFilter {
        // 定义位图的大小，一般需要定义为2的整次幂
        private final Integer cap;

        public MyBloomFilter(Integer cap) {
            this.cap = cap;
        }

        // 实现一个hash函数
        public Long hashCode(String value, Integer seed) {
            long result = 0L;
            for (int i = 0; i < value.length(); i++) {
                result = result * seed + value.charAt(i);
            }
            return result & (cap - 1);
        }
    }

    // 实现自定义处理函数
    public static class UvCountResultWithBloomFilter extends ProcessAllWindowFunction<UserBehavior, PageViewCount, TimeWindow> {
        // 定义Jedis连接和布隆过滤器
        Jedis jedis;
        MyBloomFilter myBloomFilter;

        @Override
        public void open(Configuration parameters) throws Exception {
            jedis = new Jedis("localhost", 6379);
            myBloomFilter = new MyBloomFilter(1 << 29); // 要处理1亿数据，用64M大小的位图
        }

        @Override
        public void process(Context context, Iterable<UserBehavior> elements, Collector<PageViewCount> out) throws Exception {
            // 将位图和窗口的count值全部存入redis，用windowEnd作为key
            Long windowEnd = context.window().getEnd();
            String bitMapKey = windowEnd.toString();
            // 把count值存成一张hash表
            String countHashName = "uv_count";
            String countKey = windowEnd.toString();

            // 1.取当前的userId
            Long userId = elements.iterator().next().getUserId();

            // 2.计算位图中的偏移量
            Long offset = myBloomFilter.hashCode(userId.toString(), 61);

            // 3.用redis的getbit命令，判断对应位置的值
            Boolean isExist = jedis.getbit(bitMapKey, offset);

            if (!isExist) {
                // 如果不存在，对应位图位置置1
                jedis.setbit(bitMapKey, offset, true);

                // 更新redis中保存的count值
                Long uvCount; //初始count值
                String uvCountString = jedis.hget(countHashName, countKey);
                if (uvCountString != null && !"".equals(uvCountString)) {
                    uvCount = Long.valueOf(uvCountString);
                    jedis.hset(countHashName, countKey, String.valueOf(uvCount + 1));

                    out.collect(new PageViewCount("uv", windowEnd, uvCount + 1));
                }
            }
        }

        @Override
        public void close() throws Exception {
            jedis.close();
        }
    }
}
