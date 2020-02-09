package com.ldbc.driver.runtime.metrics;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public class JsonWorkloadMetricsFormatter implements WorkloadMetricsFormatter {
    @Override
    public String format(WorkloadResultsSnapshot workloadResultsSnapshot) {
        return workloadResultsSnapshot.toJson();
    }

    @Override
    public void exportLatenciesByPercentile(WorkloadResultsSnapshot workloadResults, File resultsDir) throws IOException {

    }
}
