package com.pressir.monitor;


import com.pressir.constant.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @ClassName Statistic
 * @Description 统计结果
 * @Author pressir
 * @Date 2019-08-28 10:43
 */
class Statistic {
    private static final Logger LOGGER = LoggerFactory.getLogger(Statistic.class);
    private final int interval;
    private int min = Integer.MAX_VALUE;
    private int max = Integer.MIN_VALUE;
    private int p99 = Integer.MAX_VALUE;
    private int p95 = Integer.MAX_VALUE;
    private int p90 = Integer.MAX_VALUE;
    private int p75 = Integer.MAX_VALUE;
    private int p50 = Integer.MAX_VALUE;
    private double sucRateWCS = 0;
    private double sucRateCCS = 0;
    private double latency = 0;
    private AtomicInteger connects = new AtomicInteger(0);
    private AtomicInteger requests = new AtomicInteger(0);
    private int responses = 0;
    private int timeTaken = 0;
    private Map<Integer, Integer> timeAndCounts = new ConcurrentHashMap<>();

    Statistic(int interval) {
        this.interval = interval;
    }

    private int[] bucketSort(int max, int min, int interval, Map<Integer, Integer> timeAndCounts) {
        int[] bucket;
        if (interval > 1) {
            bucket = new int[10000];
            for (Map.Entry<Integer, Integer> entry : timeAndCounts.entrySet()) {
                bucket[(entry.getKey() - min) / interval] += entry.getValue();
            }
        } else {
            bucket = new int[max - min + 1];
            for (Map.Entry<Integer, Integer> entry : timeAndCounts.entrySet()) {
                bucket[entry.getKey() - min] += entry.getValue();
            }
        }
        return bucket;
    }

    private void calculatePercentage() {
        if (this.responses == 0) {
            return;
        }
        if (this.timeAndCounts.size() == 1) {
            for (Map.Entry<Integer, Integer> entry : timeAndCounts.entrySet()) {
                this.p50 = this.p75 = this.p90 = this.p95 = this.p99 = entry.getValue();
            }
            return;
        }

        int sum = 0;
        int interval = (this.max - this.min) / 10000 + 1;
        int[] bucket = bucketSort(this.max, this.min, interval, this.timeAndCounts);
        for (int i = 0; i < bucket.length; i++) {
            sum += bucket[i];
            if (this.p50 == Integer.MAX_VALUE && sum >= this.responses * 0.50) {
                this.p50 = this.min + i * interval;
            }
            if (this.p75 == Integer.MAX_VALUE && sum >= this.responses * 0.75) {
                this.p75 = this.min + i * interval;
            }
            if (this.p90 == Integer.MAX_VALUE && sum >= this.responses * 0.90) {
                this.p90 = this.min + i * interval;
            }
            if (this.p95 == Integer.MAX_VALUE && sum >= this.responses * 0.95) {
                this.p95 = this.min + i * interval;
            }
            if (this.p99 == Integer.MAX_VALUE && sum >= this.responses * 0.99) {
                this.p99 = this.min + i * interval;
                break;
            }
        }
    }

    void onSend() {
        this.requests.getAndIncrement();
        this.connects.getAndDecrement();
    }

    synchronized void onReceived(int time) {
        if (this.max < time) {
            this.max = time;
        }
        if (this.min > time) {
            this.min = time;
        }
        this.timeTaken += time;
        this.responses += 1;
        Integer count = this.timeAndCounts.get(time);
        if (count == null) {
            count = 0;
        }
        this.timeAndCounts.put(time, ++count);

        if (this.responses % this.interval == 0) {
            LOGGER.info("\tCompleted {} requests", this.responses);
        }
    }

    void onError(Exception e, String msg) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.error("ErrMsg: {} , Maybe caused by {}", e.getMessage() == null ? e.getStackTrace()[0].toString() : e.getMessage(), msg);
        }
    }

    void onStop() {
        // 计算分位耗时
        this.calculatePercentage();
        // 计算成功率
        if (this.requests.get() != 0) {
            this.sucRateWCS = (double) this.responses / this.requests.get() * 100;
            this.sucRateWCS = (double) ((int) (this.sucRateWCS * 100)) / 100;
        }

        this.sucRateCCS = (double) this.responses / (this.requests.get() + this.connects.get()) * 100;
        this.sucRateCCS = (double) ((int) (this.sucRateCCS * 100)) / 100;

        // 计算耗时
        if (this.responses != 0) {
            this.latency = (double) this.timeTaken / this.responses;
            this.latency = (double) ((int) (this.latency * 100)) / 100;
        }
        // 输出报告
        LOGGER.info(this.toString());
    }

    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder();
        if (this.responses % this.interval != 0) {
            stringBuilder.append("\tCompleted ").append(this.responses).append(" requests\n");
        }
        stringBuilder.append("\tFinished\n")
                .append("Time taken for successful requests: ").append((double) this.timeTaken / Constants.TIME_CONVERT_BASE).append(" seconds\n")
                .append("On connection stat: ").append(this.connects.get()).append("\n")
                .append("Send requests: ").append(this.requests.get()).append("\n")
                .append("Complete requests: ").append(this.responses).append("\n")
                .append("Failed requests: ").append(this.requests.get() - this.responses).append("\n")
                .append("Success rate without connection stat: ").append(this.sucRateWCS).append("%\n")
                .append("Success rate contains connection stat: ").append(this.sucRateCCS).append("%\n")
                .append("Time per request: ").append(this.latency == 0 ? "--" : this.latency).append(" [ms] \n")
                .append("Minimum time taken ").append(this.min == Integer.MAX_VALUE ? "--" : this.min).append(" [ms]\n")
                .append("Maximum time taken ").append(this.max == Integer.MIN_VALUE ? "--" : this.max).append(" [ms]\n")
                .append("Percentage of the requests served within a certain time (ms)\n ")
                .append("\t50% ").append(this.p50 == Integer.MAX_VALUE ? "--" : this.p50).append("\n")
                .append("\t75% ").append(this.p75 == Integer.MAX_VALUE ? "--" : this.p75).append("\n")
                .append("\t90% ").append(this.p90 == Integer.MAX_VALUE ? "--" : this.p90).append("\n")
                .append("\t95% ").append(this.p95 == Integer.MAX_VALUE ? "--" : this.p95).append("\n")
                .append("\t99% ").append(this.p99 == Integer.MAX_VALUE ? "--" : this.p99);
        return stringBuilder.toString();
    }

    void onConnect() {
        this.connects.getAndIncrement();
    }
}
