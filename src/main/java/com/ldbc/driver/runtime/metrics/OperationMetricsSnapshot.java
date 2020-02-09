package com.ldbc.driver.runtime.metrics;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.HistogramLogProcessor;
import org.HdrHistogram.HistogramLogWriter;

import java.io.*;
import java.util.concurrent.TimeUnit;

public class OperationMetricsSnapshot {
    @JsonProperty("name")
    private String name;
    @JsonProperty("unit")
    private TimeUnit durationUnit;
    @JsonProperty("count")
    private long count;
    @JsonProperty("run_time")
    private ContinuousMetricSnapshot rutTimeMetric;

    private OperationMetricsSnapshot() {
    }

    public OperationMetricsSnapshot(String name,
                                    TimeUnit durationUnit,
                                    long count,
                                    ContinuousMetricSnapshot rutTimeMetric) {
        this.name = name;
        this.durationUnit = durationUnit;
        this.count = count;
        this.rutTimeMetric = rutTimeMetric;
    }

    public String name() {
        return name;
    }

    public TimeUnit durationUnit() {
        return durationUnit;
    }

    public long count() {
        return count;
    }

    public ContinuousMetricSnapshot runTimeMetric() {
        return rutTimeMetric;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        OperationMetricsSnapshot that = (OperationMetricsSnapshot) o;

        if (count != that.count) return false;
        if (durationUnit != that.durationUnit) return false;
        if (name != null ? !name.equals(that.name) : that.name != null) return false;
        if (rutTimeMetric != null ? !rutTimeMetric.equals(that.rutTimeMetric) : that.rutTimeMetric != null)
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = name != null ? name.hashCode() : 0;
        result = 31 * result + (durationUnit != null ? durationUnit.hashCode() : 0);
        result = 31 * result + (int) (count ^ (count >>> 32));
        result = 31 * result + (rutTimeMetric != null ? rutTimeMetric.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "OperationMetricsSnapshot{" +
                "name='" + name + '\'' +
                ", durationUnit=" + durationUnit +
                ", count=" + count +
                ", rutTimeMetric=" + rutTimeMetric +
                '}';
    }

    public void saveHdrFormat(File resultsDir, String filename, long startTimeMs, long finishTimeMs) throws IOException {
        Histogram histogram = rutTimeMetric.histogram;
        histogram.setStartTimeStamp(startTimeMs);
        histogram.setEndTimeStamp(finishTimeMs);
        // save in v2 hlog format
        FileOutputStream writerStream = new FileOutputStream(resultsDir.getAbsolutePath()+"/"+filename+".hlog");
        HistogramLogWriter histogramLogWriter = new HistogramLogWriter(writerStream);
        histogramLogWriter.outputLogFormatVersion();
        histogramLogWriter.outputComment("[Logged with ldbc_snb_driver for query "+ name +"]");
        histogramLogWriter.outputStartTime(startTimeMs);
        histogramLogWriter.outputLogFormatVersion();
        histogramLogWriter.outputLegend();
        histogramLogWriter.outputIntervalHistogram(histogram);
        writerStream.close();

        // save in  percentile output format
        FileOutputStream writerStreamText = new FileOutputStream(resultsDir.getAbsolutePath()+"/"+filename+".txt");
        histogram.outputPercentileDistribution(new PrintStream(writerStreamText), 1000.0);
        writerStreamText.close();
    }
}
