package com.ldbc.driver.runtime.metrics;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public interface WorkloadMetricsFormatter
{
    String format( WorkloadResultsSnapshot workloadResultsSnapshot );

    void exportLatenciesByPercentile(WorkloadResultsSnapshot workloadResults, File resultsDir) throws IOException;
}
