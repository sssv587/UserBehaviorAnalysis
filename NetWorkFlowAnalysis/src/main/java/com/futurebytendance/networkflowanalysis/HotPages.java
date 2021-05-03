package com.futurebytendance.networkflowanalysis;

import com.futurebytendance.networkflowanalysis.bean.ApacheLogEvent;
import com.futurebytendance.networkflowanalysis.bean.PageViewCount;
import org.apache.flink.api.common.functions.AggregateFunction;
import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.calcite.shaded.com.google.common.collect.Lists;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.streaming.api.functions.timestamps.BoundedOutOfOrdernessTimestampExtractor;
import org.apache.flink.streaming.api.functions.windowing.WindowFunction;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;

import java.net.URL;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * @author yuhang.sun
 * @version 1.0
 * @date 2021/5/3 - 16:17
 * @Description 实时热门页面统计
 */
public class HotPages {
    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);
        env.setParallelism(1);

        // 读取文件，转成POJO
//        URL resource = HotPages.class.getResource("/apache.log");
//        DataStreamSource<String> inputStream = env.readTextFile(resource.getPath());
        DataStream<String> inputStream = env.socketTextStream("localhost", 7777);

        DataStream<ApacheLogEvent> dataStream = inputStream.map(
                (MapFunction<String, ApacheLogEvent>) value -> {
                    String[] fields = value.split(" ");
                    SimpleDateFormat simpleDateFormat = new SimpleDateFormat("dd/MM/yyyy:HH:mm:ss");
                    Long timestamp = simpleDateFormat.parse(fields[3]).getTime();
                    return new ApacheLogEvent(fields[0], fields[1], timestamp, fields[5], fields[6]);
                }
        ).assignTimestampsAndWatermarks(new BoundedOutOfOrdernessTimestampExtractor<ApacheLogEvent>(Time.seconds(1)) {
            @Override
            public long extractTimestamp(ApacheLogEvent element) {
                return element.getTimestamp();
            }
        });

        dataStream.print("data");

        // 分组开窗聚合
        // 定义一个侧输出流标签
        OutputTag<ApacheLogEvent> lateTag = new OutputTag<ApacheLogEvent>("late");
        SingleOutputStreamOperator<PageViewCount> windowAggStream = dataStream.filter((FilterFunction<ApacheLogEvent>) value -> {
            return "GET".equals(value.getMethod()); // 过滤get请求
        }).filter(data -> {
            String regex = "^((?!\\.(css|js|png|ico)$).)*$";
            return Pattern.matches(regex, data.getUrl());
        })
                .keyBy(ApacheLogEvent::getUrl) // 按照url分组
                .timeWindow(Time.minutes(10), Time.seconds(5))
                .allowedLateness(Time.minutes(1))
                .sideOutputLateData(lateTag)
                .aggregate(new PageCountAgg(), new PageCountResult());

        windowAggStream.getSideOutput(lateTag).print("late");

        // 收集同一窗口count数据，排序输出
        DataStream<String> resultStream = windowAggStream.keyBy(PageViewCount::getWindowEnd)
                .process(new TopNHotPages(3));

        resultStream.print();

        env.execute("hot page job");
    }

    // 自定义预聚合函数
    public static class PageCountAgg implements AggregateFunction<ApacheLogEvent, Long, Long> {

        @Override
        public Long createAccumulator() {
            return 0L;
        }

        @Override
        public Long add(ApacheLogEvent value, Long accumulator) {
            return accumulator + 1;
        }

        @Override
        public Long getResult(Long accumulator) {
            return accumulator;
        }

        @Override
        public Long merge(Long a, Long b) {
            return a + b;
        }
    }

    // 实现自定义的窗口函数
    public static class PageCountResult implements WindowFunction<Long, PageViewCount, String, TimeWindow> {

        @Override
        public void apply(String url, TimeWindow window, Iterable<Long> input, Collector<PageViewCount> out) throws Exception {
            out.collect(new PageViewCount(url, window.getEnd(), input.iterator().next()));
        }
    }

    // 实现自定义处理函数
    public static class TopNHotPages extends KeyedProcessFunction<Long, PageViewCount, String> {
        private final Integer topSize;

        public TopNHotPages(Integer topSize) {
            this.topSize = topSize;
        }

        // 定义状态，保存当前所有PageViewCount到List中
//        ListState<PageViewCount> pageViewCountListState;
        MapState<String, Long> pageViewCountMapState;

        @Override
        public void open(Configuration parameters) throws Exception {
//            pageViewCountListState = getRuntimeContext().getListState(new ListStateDescriptor<>("page-count-list", PageViewCount.class));
            pageViewCountMapState = getRuntimeContext().getMapState(new MapStateDescriptor<>("page-count-map", String.class, Long.class));
        }

        @Override
        public void processElement(PageViewCount value, Context ctx, Collector<String> out) throws Exception {
//            pageViewCountListState.add(value);
            pageViewCountMapState.put(value.getUrl(), value.getCount());
            ctx.timerService().registerEventTimeTimer(value.getWindowEnd() + 1);
            // 注册一个一分钟之后的定时器，用来情况状态
            ctx.timerService().registerEventTimeTimer(value.getWindowEnd() + 60 * 1000);
        }

        @Override
        public void onTimer(long timestamp, OnTimerContext ctx, Collector<String> out) throws Exception {
            // 先判断是否到了窗口关闭清理时间，如果是，直接清空状态返回
            if (timestamp == ctx.getCurrentKey() + 60 * 1000L) {
                pageViewCountMapState.clear();
                return;
            }

            ArrayList<Map.Entry<String, Long>> pageViewCounts = Lists.newArrayList(pageViewCountMapState.entries());

            pageViewCounts.sort((o1, o2) -> o2.getValue().compareTo(o1.getValue()));

            // 格式化成字符串输出
            // 将排名信息格式化成String，方便打印输出
            StringBuilder resultBuilder = new StringBuilder();
            resultBuilder.append("===================================\n");
            resultBuilder.append("窗口结束时间:").append(new Timestamp(timestamp - 1)).append("\n");

            // 遍历列表，取topN输出
            for (int i = 0; i < Math.min(topSize, pageViewCounts.size()); i++) {
                Map.Entry<String, Long> pageViewCount = pageViewCounts.get(i);
                resultBuilder.append("No ").append(i + 1).append(":")
                        .append(" 页面URL = ").append(pageViewCount.getKey())
                        .append(" 浏览量 = ").append(pageViewCount.getValue())
                        .append("\n");
            }
            resultBuilder.append("===================================\n\n");

            // 控制输出频率
            Thread.sleep(1000L);

            out.collect(resultBuilder.toString());

//            pageViewCountListState.clear();
        }


    }
}
