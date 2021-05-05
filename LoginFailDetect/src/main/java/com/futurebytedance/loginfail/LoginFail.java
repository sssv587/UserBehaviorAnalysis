package com.futurebytedance.loginfail;

import com.futurebytedance.loginfail.beans.LoginEvent;
import com.futurebytedance.loginfail.beans.LoginFailWarning;
import org.apache.commons.compress.utils.Lists;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.streaming.api.functions.timestamps.BoundedOutOfOrdernessTimestampExtractor;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.util.Collector;

import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * @author yuhang.sun
 * @version 1.0
 * @date 2021/5/5 - 21:21
 * @Description 恶意登录检测
 */
public class LoginFail {
    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);

        // 1.从文件中读取数据
        URL resource = LoginFail.class.getResource("/LoginLog.csv");
        SingleOutputStreamOperator<LoginEvent> loginEventStream = env.readTextFile(resource.getPath())
                .map((MapFunction<String, LoginEvent>) value -> {
                    String[] fields = value.split(",");
                    return new LoginEvent(new Long(fields[0]), fields[1], fields[2], new Long(fields[3]));
                }).assignTimestampsAndWatermarks(new BoundedOutOfOrdernessTimestampExtractor<LoginEvent>(Time.seconds(3)) {
                    @Override
                    public long extractTimestamp(LoginEvent element) {
                        return element.getTimestamp() * 1000L;
                    }
                });

        // 自定义处理函数检测连续登录失败事件情况
        SingleOutputStreamOperator<LoginFailWarning> warningStream = loginEventStream.keyBy(LoginEvent::getUserId)
                .process(new LoginFailDetectWarning(2));

        warningStream.print();

        env.execute("login fail detect job");
    }

    public static class LoginFailDetectWarning extends KeyedProcessFunction<Long, LoginEvent, LoginFailWarning> {
        // 定义属性，最大连续登录失败次数
        private final Integer maxFailTimes;

        public LoginFailDetectWarning(Integer maxFailTimes) {
            this.maxFailTimes = maxFailTimes;
        }

        // 定义状态：保存2秒内所有的登录失败事件
        ListState<LoginEvent> loginFailEventListState;

        @Override
        public void open(Configuration parameters) {
            loginFailEventListState = getRuntimeContext().getListState(new ListStateDescriptor<>("login-fail-list", LoginEvent.class));
        }

        // 以登录事件作为判断报警的触发条件，不再注册定时器
        @Override
        public void processElement(LoginEvent value, Context ctx, Collector<LoginFailWarning> out) throws Exception {
            // 判断当前事件登录状态
            if ("fail".equals(value.getLoginState())) {
                // 1.如果是登录失败，获取状态中之前的登录失败事件，继续判断是否已有
                Iterator<LoginEvent> iterator = loginFailEventListState.get().iterator();
                if (iterator.hasNext()) {
                    // 1.1 如果已经有登陆失败事件，继续判断时间戳是否在2秒之内
                    // 获取已有的登陆失败事件
                    LoginEvent firstEventFailEvent = iterator.next();
                    if (value.getTimestamp() - firstEventFailEvent.getTimestamp() <= maxFailTimes) {
                        // 1.1.1 如果在2s内，输出报警
                        out.collect(new LoginFailWarning(value.getUserId(),
                                firstEventFailEvent.getTimestamp(), value.getTimestamp(), "login fail in 2 seconds"));

                    }
                    // 不管报不报警，这次都已经处理完毕，直接更新状态
                    loginFailEventListState.clear();
                    loginFailEventListState.add(value);
                } else {
                    // 1.2 如果没有登陆失败，直接将当前事件存储列表状态
                    loginFailEventListState.add(value);
                }
            } else {
                // 2.如果是登录成功，直接清空状态
                loginFailEventListState.clear();
            }
        }
    }

    // 实现自定义keyedProcessFunction
    public static class LoginFailDetectWarning0 extends KeyedProcessFunction<Long, LoginEvent, LoginFailWarning> {
        // 定义属性，最大连续登录失败次数
        private final Integer maxFailTimes;

        public LoginFailDetectWarning0(Integer maxFailTimes) {
            this.maxFailTimes = maxFailTimes;
        }

        // 定义状态：保存2秒内所有的登录失败事件
        ListState<LoginEvent> loginFailEventListState;
        // 定义状态：保存注册的定时器时间戳
        ValueState<Long> timerTsState;

        @Override
        public void open(Configuration parameters) {
            loginFailEventListState = getRuntimeContext().getListState(new ListStateDescriptor<>("login-fail-list", LoginEvent.class));
            timerTsState = getRuntimeContext().getState(new ValueStateDescriptor<>("timer-ts", Long.class, null));
        }

        @Override
        public void processElement(LoginEvent value, Context ctx, Collector<LoginFailWarning> out) throws Exception {
            // 判断当前登录事件类型
            if ("fail".equals(value.getLoginState())) {
                // 如果是失败事件，添加到列表状态中
                loginFailEventListState.add(value);
                // 如果没有定时器，注册一个2秒之后的定时器
                if (timerTsState.value() == null) {
                    long ts = (value.getTimestamp() + 2) * 1000;
                    ctx.timerService().registerEventTimeTimer(ts);
                    timerTsState.update(ts);
                }
            } else {
                // 2.如果是登陆成功，删除定时器，情况状态，重新开始
                if (timerTsState.value() != null) {
                    ctx.timerService().deleteEventTimeTimer(timerTsState.value());
                    loginFailEventListState.clear();
                    timerTsState.clear();
                }
            }
        }

        @Override
        public void onTimer(long timestamp, OnTimerContext ctx, Collector<LoginFailWarning> out) throws Exception {
            // 定时器触发，说明2秒内没有登陆成功来，判断ListState中失败的个数
            ArrayList<LoginEvent> loginFailEvents = Lists.newArrayList(loginFailEventListState.get().iterator());
            int failTimes = loginFailEvents.size();
            if (failTimes >= maxFailTimes) {
                // 如果超出设定的最大失败次数，输出报警
                out.collect(new LoginFailWarning(ctx.getCurrentKey(),
                        loginFailEvents.get(0).getTimestamp(),
                        loginFailEvents.get(failTimes - 1).getTimestamp(),
                        "login fail in 2s for " + failTimes + " times"));
            }

            // 情况状态
            loginFailEventListState.clear();
            timerTsState.clear();
        }
    }
}
