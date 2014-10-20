package com.ldbc.driver.runtime;

import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.ldbc.driver.*;
import com.ldbc.driver.control.ConcurrentControlService;
import com.ldbc.driver.control.ConsoleAndFileDriverConfiguration;
import com.ldbc.driver.control.DriverConfigurationException;
import com.ldbc.driver.control.LocalControlService;
import com.ldbc.driver.generator.GeneratorFactory;
import com.ldbc.driver.generator.RandomDataGeneratorFactory;
import com.ldbc.driver.runtime.coordination.CompletionTimeException;
import com.ldbc.driver.runtime.coordination.CompletionTimeServiceAssistant;
import com.ldbc.driver.runtime.coordination.ConcurrentCompletionTimeService;
import com.ldbc.driver.runtime.metrics.ConcurrentMetricsService;
import com.ldbc.driver.runtime.metrics.MetricsCollectionException;
import com.ldbc.driver.runtime.metrics.ThreadedQueuedConcurrentMetricsService;
import com.ldbc.driver.runtime.metrics.WorkloadResultsSnapshot;
import com.ldbc.driver.runtime.scheduling.ErrorReportingTerminatingExecutionDelayPolicy;
import com.ldbc.driver.runtime.scheduling.ExecutionDelayPolicy;
import com.ldbc.driver.temporal.Duration;
import com.ldbc.driver.temporal.SystemTimeSource;
import com.ldbc.driver.temporal.Time;
import com.ldbc.driver.temporal.TimeSource;
import com.ldbc.driver.testutils.TestUtils;
import com.ldbc.driver.util.csv.SimpleCsvFileReader;
import com.ldbc.driver.util.csv.SimpleCsvFileWriter;
import com.ldbc.driver.util.MapUtils;
import com.ldbc.driver.util.Tuple;
import com.ldbc.driver.workloads.ldbc.snb.interactive.LdbcSnbInteractiveConfiguration;
import com.ldbc.driver.workloads.ldbc.snb.interactive.LdbcSnbInteractiveWorkload;
import com.ldbc.driver.workloads.ldbc.snb.interactive.db.DummyLdbcSnbInteractiveDb;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

public class WorkloadRunnerTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();
    private static final long ONE_SECOND_AS_NANO = Time.fromSeconds(1).asNano();

    TimeSource timeSource = new SystemTimeSource();
    CompletionTimeServiceAssistant completionTimeServiceAssistant = new CompletionTimeServiceAssistant();

    @Test
    public void shouldRunReadOnlyLdbcWorkloadWithNothingDbAndReturnExpectedMetrics()
            throws InterruptedException, DbException, WorkloadException, IOException, MetricsCollectionException, CompletionTimeException, DriverConfigurationException {
        List<Integer> threadCounts = Lists.newArrayList(1, 2, 4);
        long operationCount = 1000;
        for (int threadCount : threadCounts) {
            doShouldRunReadOnlyLdbcWorkloadWithNothingDbAndReturnExpectedMetricsIncludingResultsLog(threadCount, operationCount);
        }
    }

    public void doShouldRunReadOnlyLdbcWorkloadWithNothingDbAndReturnExpectedMetricsIncludingResultsLog(int threadCount, long operationCount)
            throws InterruptedException, DbException, WorkloadException, IOException, MetricsCollectionException, CompletionTimeException, DriverConfigurationException {
        ConcurrentControlService controlService = null;
        Db db = null;
        Workload workload = null;
        ConcurrentMetricsService metricsService = null;
        ConcurrentCompletionTimeService completionTimeService = null;
        ConcurrentErrorReporter errorReporter = new ConcurrentErrorReporter();
        try {
            Map<String, String> paramsMap = LdbcSnbInteractiveConfiguration.defaultReadOnlyConfig();
            paramsMap.put(LdbcSnbInteractiveConfiguration.PARAMETERS_DIRECTORY, TestUtils.getResource("/").getAbsolutePath());
            List<String> forumUpdateFiles = Lists.newArrayList(TestUtils.getResource("/updateStream_0_0_forum.csv").getAbsolutePath());
            paramsMap.put(LdbcSnbInteractiveConfiguration.FORUM_UPDATE_FILES, LdbcSnbInteractiveConfiguration.serializeFilePathsListFromConfiguration(forumUpdateFiles));
            List<String> personUpdateFiles = Lists.newArrayList(TestUtils.getResource("/updateStream_0_0_person.csv").getAbsolutePath());
            paramsMap.put(LdbcSnbInteractiveConfiguration.PERSON_UPDATE_FILES, LdbcSnbInteractiveConfiguration.serializeFilePathsListFromConfiguration(personUpdateFiles));
            // Driver-specific parameters
            String name = null;
            String dbClassName = DummyLdbcSnbInteractiveDb.class.getName();
            String workloadClassName = LdbcSnbInteractiveWorkload.class.getName();
            Duration statusDisplayInterval = Duration.fromSeconds(0);
            TimeUnit timeUnit = TimeUnit.NANOSECONDS;
            String resultDirPath = temporaryFolder.newFolder().getAbsolutePath();
            double timeCompressionRatio = 0.0001;
            Duration windowedExecutionWindowDuration = Duration.fromSeconds(1);
            Set<String> peerIds = new HashSet<>();
            Duration toleratedExecutionDelay = Duration.fromMinutes(60);
            ConsoleAndFileDriverConfiguration.ConsoleAndFileValidationParamOptions validationParams = null;
            String dbValidationFilePath = null;
            boolean validateWorkload = false;
            boolean calculateWorkloadStatistics = false;
            Duration spinnerSleepDuration = Duration.fromMilli(0);
            boolean printHelp = false;
            boolean ignoreScheduledStartTimes = false;
            boolean shouldCreateResultsLog = true;

            ConsoleAndFileDriverConfiguration configuration = new ConsoleAndFileDriverConfiguration(
                    paramsMap,
                    name,
                    dbClassName,
                    workloadClassName,
                    operationCount,
                    threadCount,
                    statusDisplayInterval,
                    timeUnit,
                    resultDirPath,
                    timeCompressionRatio,
                    windowedExecutionWindowDuration,
                    peerIds,
                    toleratedExecutionDelay,
                    validationParams,
                    dbValidationFilePath,
                    validateWorkload,
                    calculateWorkloadStatistics,
                    spinnerSleepDuration,
                    printHelp,
                    ignoreScheduledStartTimes,
                    shouldCreateResultsLog
            );

            configuration = (ConsoleAndFileDriverConfiguration) configuration.applyMap(MapUtils.loadPropertiesToMap(TestUtils.getResource("/updateStream.properties")));

            controlService = new LocalControlService(timeSource.now(), configuration);
            db = new DummyLdbcSnbInteractiveDb();
            db.init(configuration.asMap());

            GeneratorFactory gf = new GeneratorFactory(new RandomDataGeneratorFactory(42L));
            Tuple.Tuple2<WorkloadStreams, Workload> workloadStreamsAndWorkload =
                    WorkloadStreams.createNewWorkloadWithLimitedWorkloadStreams(configuration, gf);

            workload = workloadStreamsAndWorkload._2();

            WorkloadStreams workloadStreams = WorkloadStreams.timeOffsetAndCompressWorkloadStreams(
                    workloadStreamsAndWorkload._1(),
                    controlService.workloadStartTime(),
                    configuration.timeCompressionRatio(),
                    gf
            );

            boolean recordStartTimeDelayLatency = false == configuration.ignoreScheduledStartTimes();
            ExecutionDelayPolicy executionDelayPolicy = new ErrorReportingTerminatingExecutionDelayPolicy(
                    timeSource,
                    toleratedExecutionDelay,
                    errorReporter);
            File resultsLog = temporaryFolder.newFile();
            SimpleCsvFileWriter csvResultsLogWriter = new SimpleCsvFileWriter(resultsLog, SimpleCsvFileWriter.DEFAULT_COLUMN_SEPARATOR);
            metricsService = ThreadedQueuedConcurrentMetricsService.newInstanceUsingBlockingQueue(
                    timeSource,
                    errorReporter,
                    configuration.timeUnit(),
                    controlService.workloadStartTime(),
                    ThreadedQueuedConcurrentMetricsService.DEFAULT_HIGHEST_EXPECTED_RUNTIME_DURATION,
                    recordStartTimeDelayLatency,
                    executionDelayPolicy,
                    csvResultsLogWriter);

            ConcurrentCompletionTimeService concurrentCompletionTimeService =
                    completionTimeServiceAssistant.newSynchronizedConcurrentCompletionTimeServiceFromPeerIds(
                            controlService.configuration().peerIds());

            int boundedQueueSize = DefaultQueues.DEFAULT_BOUND_100;
            WorkloadRunner runner = new WorkloadRunner(
                    timeSource,
                    db,
                    workloadStreams,
                    metricsService,
                    errorReporter,
                    concurrentCompletionTimeService,
                    controlService.configuration().threadCount(),
                    controlService.configuration().statusDisplayInterval(),
                    controlService.workloadStartTime(),
                    controlService.configuration().spinnerSleepDuration(),
                    controlService.configuration().windowedExecutionWindowDuration(),
                    WorkloadRunner.DEFAULT_DURATION_TO_WAIT_FOR_ALL_HANDLERS_TO_FINISH,
                    controlService.configuration().ignoreScheduledStartTimes(),
                    boundedQueueSize);

            runner.executeWorkload();

            WorkloadResultsSnapshot workloadResults = metricsService.results();

            assertThat(errorReporter.toString() + "\n" + workloadResults.toString(), errorReporter.errorEncountered(), is(false));
            assertThat(errorReporter.toString() + "\n" + workloadResults.toString(), workloadResults.startTime().gte(controlService.workloadStartTime()), is(true));
            assertThat(errorReporter.toString() + "\n" + workloadResults.toString(), workloadResults.startTime().lt(controlService.workloadStartTime().plus(configuration.toleratedExecutionDelay())), is(true));
            assertThat(errorReporter.toString() + "\n" + workloadResults.toString(), workloadResults.latestFinishTime().gte(workloadResults.startTime()), is(true));
            assertThat(errorReporter.toString() + "\n" + workloadResults.toString(), workloadResults.totalOperationCount(), is(operationCount));

            WorkloadResultsSnapshot workloadResultsFromJson = WorkloadResultsSnapshot.fromJson(workloadResults.toJson());

            assertThat(errorReporter.toString(), workloadResults, equalTo(workloadResultsFromJson));
            assertThat(errorReporter.toString(), workloadResults.toJson(), equalTo(workloadResultsFromJson.toJson()));

            csvResultsLogWriter.close();
            SimpleCsvFileReader csvResultsLogReader = new SimpleCsvFileReader(resultsLog, SimpleCsvFileReader.DEFAULT_COLUMN_SEPARATOR_PATTERN);
            assertThat((long) Iterators.size(csvResultsLogReader), is(configuration.operationCount())); // NOT + 1 because I didn't add csv headers
            csvResultsLogReader.close();

            double operationsPerSecond = Math.round(((double) operationCount / workloadResults.totalRunDuration().asNano()) * ONE_SECOND_AS_NANO);
            double microSecondPerOperation = (double) workloadResults.totalRunDuration().asMicro() / operationCount;
            System.out.println(String.format("[%s threads] Completed %s operations in %s = %s op/sec = 1 op/%s us", threadCount, operationCount, workloadResults.totalRunDuration(), operationsPerSecond, microSecondPerOperation));
        } finally {
            System.out.println(errorReporter.toString());
            if (null != controlService) controlService.shutdown();
            if (null != db) db.shutdown();
            if (null != workload) workload.cleanup();
            if (null != metricsService) metricsService.shutdown();
            if (null != completionTimeService) completionTimeService.shutdown();
        }
    }

    @Test
    public void shouldRunReadOnlyLdbcWorkloadWithNothingDbWhileIgnoringScheduledStartTimesUsingSynchronizedCompletionTimeServiceAndReturnExpectedMetrics()
            throws InterruptedException, DbException, WorkloadException, IOException, MetricsCollectionException, CompletionTimeException, DriverConfigurationException {
        List<Integer> threadCounts = Lists.newArrayList(1, 2, 4);
        long operationCount = 1000000;
        for (int threadCount : threadCounts) {
            ConcurrentErrorReporter errorReporter = new ConcurrentErrorReporter();
            ConcurrentCompletionTimeService completionTimeService =
                    completionTimeServiceAssistant.newSynchronizedConcurrentCompletionTimeServiceFromPeerIds(new HashSet<String>());
            try {
                doShouldRunReadOnlyLdbcWorkloadWithNothingDbWhileIgnoringScheduledStartTimesAndReturnExpectedMetrics(threadCount, operationCount, completionTimeService, errorReporter);
            } finally {
                completionTimeService.shutdown();
            }
        }
    }

    @Test
    public void shouldRunReadOnlyLdbcWorkloadWithNothingDbWhileIgnoringScheduledStartTimesUsingThreadedCompletionTimeServiceAndReturnExpectedMetrics()
            throws InterruptedException, DbException, WorkloadException, IOException, MetricsCollectionException, CompletionTimeException, DriverConfigurationException {
        List<Integer> threadCounts = Lists.newArrayList(1, 2, 4);
        long operationCount = 1000000;
        for (int threadCount : threadCounts) {
            ConcurrentErrorReporter errorReporter = new ConcurrentErrorReporter();
            ConcurrentCompletionTimeService completionTimeService =
                    completionTimeServiceAssistant.newThreadedQueuedConcurrentCompletionTimeServiceFromPeerIds(new SystemTimeSource(), new HashSet<String>(), new ConcurrentErrorReporter());
            try {
                doShouldRunReadOnlyLdbcWorkloadWithNothingDbWhileIgnoringScheduledStartTimesAndReturnExpectedMetrics(threadCount, operationCount, completionTimeService, errorReporter);
            } finally {
                completionTimeService.shutdown();
            }
        }
    }

    public void doShouldRunReadOnlyLdbcWorkloadWithNothingDbWhileIgnoringScheduledStartTimesAndReturnExpectedMetrics(int threadCount,
                                                                                                                     long operationCount,
                                                                                                                     ConcurrentCompletionTimeService completionTimeService,
                                                                                                                     ConcurrentErrorReporter errorReporter)
            throws InterruptedException, DbException, WorkloadException, IOException, MetricsCollectionException, CompletionTimeException, DriverConfigurationException {
        ConcurrentControlService controlService = null;
        Db db = null;
        Workload workload = null;
        ConcurrentMetricsService metricsService = null;
        try {
            Map<String, String> paramsMap = LdbcSnbInteractiveConfiguration.defaultReadOnlyConfig();
            paramsMap.put(LdbcSnbInteractiveConfiguration.PARAMETERS_DIRECTORY, TestUtils.getResource("/").getAbsolutePath());
            List<String> forumUpdateFiles = Lists.newArrayList(TestUtils.getResource("/updateStream_0_0_forum.csv").getAbsolutePath());
            paramsMap.put(LdbcSnbInteractiveConfiguration.FORUM_UPDATE_FILES, LdbcSnbInteractiveConfiguration.serializeFilePathsListFromConfiguration(forumUpdateFiles));
            List<String> personUpdateFiles = Lists.newArrayList(TestUtils.getResource("/updateStream_0_0_person.csv").getAbsolutePath());
            paramsMap.put(LdbcSnbInteractiveConfiguration.PERSON_UPDATE_FILES, LdbcSnbInteractiveConfiguration.serializeFilePathsListFromConfiguration(personUpdateFiles));
            // Driver-specific parameters
            String name = null;
            String dbClassName = DummyLdbcSnbInteractiveDb.class.getName();
            String workloadClassName = LdbcSnbInteractiveWorkload.class.getName();
            Duration statusDisplayInterval = Duration.fromSeconds(0);
            TimeUnit timeUnit = TimeUnit.NANOSECONDS;
            String resultDirPath = temporaryFolder.newFolder().getAbsolutePath();
            double timeCompressionRatio = 1.0;
            Duration windowedExecutionWindowDuration = Duration.fromSeconds(1);
            Set<String> peerIds = new HashSet<>();
            Duration toleratedExecutionDelay = Duration.fromMinutes(60);
            ConsoleAndFileDriverConfiguration.ConsoleAndFileValidationParamOptions validationParams = null;
            String dbValidationFilePath = null;
            boolean validateWorkload = false;
            boolean calculateWorkloadStatistics = false;
            Duration spinnerSleepDuration = Duration.fromMilli(0);
            boolean printHelp = false;
            boolean ignoreScheduledStartTimes = true;
            boolean shouldCreateResultsLog = true;

            ConsoleAndFileDriverConfiguration configuration = new ConsoleAndFileDriverConfiguration(
                    paramsMap,
                    name,
                    dbClassName,
                    workloadClassName,
                    operationCount,
                    threadCount,
                    statusDisplayInterval,
                    timeUnit,
                    resultDirPath,
                    timeCompressionRatio,
                    windowedExecutionWindowDuration,
                    peerIds,
                    toleratedExecutionDelay,
                    validationParams,
                    dbValidationFilePath,
                    validateWorkload,
                    calculateWorkloadStatistics,
                    spinnerSleepDuration,
                    printHelp,
                    ignoreScheduledStartTimes,
                    shouldCreateResultsLog
            );

            configuration = (ConsoleAndFileDriverConfiguration) configuration.applyMap(MapUtils.loadPropertiesToMap(TestUtils.getResource("/updateStream.properties")));

            controlService = new LocalControlService(timeSource.now().plus(Duration.fromMilli(1000)), configuration);
            db = new DummyLdbcSnbInteractiveDb();
            db.init(configuration.asMap());
            workload = new LdbcSnbInteractiveWorkload();
            workload.init(configuration);
            GeneratorFactory gf = new GeneratorFactory(new RandomDataGeneratorFactory(42L));
            Iterator<Operation<?>> operations = gf.limit(workload.streams(gf).mergeSortedByStartTime(gf), configuration.operationCount());
            Iterator<Operation<?>> timeMappedOperations = gf.timeOffsetAndCompress(operations, controlService.workloadStartTime(), 1.0);
            WorkloadStreams workloadStreams = new WorkloadStreams();
            workloadStreams.setAsynchronousStream(
                    new HashSet<Class<? extends Operation<?>>>(),
                    Collections.<Operation<?>>emptyIterator(),
                    timeMappedOperations
            );
            boolean recordStartTimeDelayLatency = false == configuration.ignoreScheduledStartTimes();
            ExecutionDelayPolicy executionDelayPolicy = new ErrorReportingTerminatingExecutionDelayPolicy(
                    timeSource,
                    toleratedExecutionDelay,
                    errorReporter);
            File resultsLog = temporaryFolder.newFile();
            SimpleCsvFileWriter csvResultsLogWriter = new SimpleCsvFileWriter(resultsLog, SimpleCsvFileWriter.DEFAULT_COLUMN_SEPARATOR);
            metricsService = ThreadedQueuedConcurrentMetricsService.newInstanceUsingBlockingQueue(
                    timeSource,
                    errorReporter,
                    configuration.timeUnit(),
                    controlService.workloadStartTime(),
                    ThreadedQueuedConcurrentMetricsService.DEFAULT_HIGHEST_EXPECTED_RUNTIME_DURATION,
                    recordStartTimeDelayLatency,
                    executionDelayPolicy,
                    csvResultsLogWriter);

            int boundedQueueSize = DefaultQueues.DEFAULT_BOUND_100;
            WorkloadRunner runner = new WorkloadRunner(
                    timeSource,
                    db,
                    workloadStreams,
                    metricsService,
                    errorReporter,
                    completionTimeService,
                    controlService.configuration().threadCount(),
                    controlService.configuration().statusDisplayInterval(),
                    controlService.workloadStartTime(),
                    controlService.configuration().spinnerSleepDuration(),
                    controlService.configuration().windowedExecutionWindowDuration(),
                    WorkloadRunner.DEFAULT_DURATION_TO_WAIT_FOR_ALL_HANDLERS_TO_FINISH,
                    controlService.configuration().ignoreScheduledStartTimes(),
                    boundedQueueSize);

            runner.executeWorkload();

            WorkloadResultsSnapshot workloadResults = metricsService.results();

            assertThat(errorReporter.toString() + "\n" + workloadResults.toString(), errorReporter.errorEncountered(), is(false));
            assertThat(errorReporter.toString() + "\n" + workloadResults.toString(), workloadResults.startTime().gte(controlService.workloadStartTime()), is(true));
            assertThat(errorReporter.toString() + "\n" + workloadResults.toString(), workloadResults.startTime().lt(controlService.workloadStartTime().plus(configuration.toleratedExecutionDelay())), is(true));
            assertThat(errorReporter.toString() + "\n" + workloadResults.toString(), workloadResults.latestFinishTime().gte(workloadResults.startTime()), is(true));
            assertThat(errorReporter.toString() + "\n" + workloadResults.toString(), workloadResults.totalOperationCount(), is(operationCount));

            WorkloadResultsSnapshot workloadResultsFromJson = WorkloadResultsSnapshot.fromJson(workloadResults.toJson());

            assertThat(errorReporter.toString(), workloadResults, equalTo(workloadResultsFromJson));
            assertThat(errorReporter.toString(), workloadResults.toJson(), equalTo(workloadResultsFromJson.toJson()));

            csvResultsLogWriter.close();
            SimpleCsvFileReader csvResultsLogReader = new SimpleCsvFileReader(resultsLog, SimpleCsvFileReader.DEFAULT_COLUMN_SEPARATOR_PATTERN);
            assertThat((long) Iterators.size(csvResultsLogReader), is(configuration.operationCount())); // NOT + 1 because I didn't add csv headers
            csvResultsLogReader.close();

            double operationsPerSecond = Math.round(((double) operationCount / workloadResults.totalRunDuration().asNano()) * ONE_SECOND_AS_NANO);
            double microSecondPerOperation = (double) workloadResults.totalRunDuration().asMicro() / operationCount;
            System.out.println(String.format("[%s threads] Completed %s operations in %s = %s op/sec = 1 op/%s us", threadCount, operationCount, workloadResults.totalRunDuration(), operationsPerSecond, microSecondPerOperation));
        } finally {
            System.out.println(errorReporter.toString());
            if (null != controlService) controlService.shutdown();
            if (null != db) db.shutdown();
            if (null != workload) workload.cleanup();
            if (null != metricsService) metricsService.shutdown();
            if (null != completionTimeService) completionTimeService.shutdown();
        }
    }
}
