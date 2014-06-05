package com.ldbc.driver;

import com.ldbc.driver.control.ConcurrentControlService;
import com.ldbc.driver.control.ConsoleAndFileDriverConfiguration;
import com.ldbc.driver.control.DriverConfigurationException;
import com.ldbc.driver.control.LocalControlService;
import com.ldbc.driver.generator.GeneratorFactory;
import com.ldbc.driver.runtime.ConcurrentErrorReporter;
import com.ldbc.driver.runtime.WorkloadRunner;
import com.ldbc.driver.runtime.coordination.CompletionTimeException;
import com.ldbc.driver.runtime.coordination.ConcurrentCompletionTimeService;
import com.ldbc.driver.runtime.coordination.ThreadedQueuedConcurrentCompletionTimeService;
import com.ldbc.driver.runtime.metrics.*;
import com.ldbc.driver.temporal.Duration;
import com.ldbc.driver.temporal.Time;
import com.ldbc.driver.util.ClassLoaderHelper;
import com.ldbc.driver.util.RandomDataGeneratorFactory;
import org.apache.log4j.Logger;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.Iterator;
import java.util.concurrent.Future;

public class Client {
    private static Logger logger = Logger.getLogger(Client.class);

    private static final long RANDOM_SEED = 42;

    public static void main(String[] args) throws ClientException {
        try {
            ConsoleAndFileDriverConfiguration configuration = ConsoleAndFileDriverConfiguration.fromArgs(args);
            // TODO this method will not work with multiple processes - should come from controlService
            Time workloadStartTime = Time.now().plus(Duration.fromMilli(1000));
            ConcurrentControlService controlService = new LocalControlService(workloadStartTime, configuration);
            Client client = new Client(controlService);
            client.start();
        } catch (DriverConfigurationException e) {
            String errMsg = String.format("Error parsing parameters: %s", e.getMessage());
            logger.error(errMsg);
        } catch (Exception e) {
            logger.error("Client terminated unexpectedly\n" + ConcurrentErrorReporter.stackTraceToString(e));
        }
    }

    private final Workload workload;
    private final Db db;
    private final ConcurrentControlService controlService;
    private final ConcurrentMetricsService metricsService;
    private final ConcurrentCompletionTimeService completionTimeService;
    private final WorkloadRunner workloadRunner;

    public Client(ConcurrentControlService controlService) throws ClientException {
        this.controlService = controlService;

        try {
            workload = ClassLoaderHelper.loadWorkload(controlService.configuration().workloadClassName());
            workload.init(controlService.configuration());
            // TODO add check that all ExecutionMode:GctMode combinations make sense (e.g., Partial+GctNone does not make sense unless window size can somehow be specified)
        } catch (Exception e) {
            throw new ClientException(String.format("Error loading Workload class: %s", controlService.configuration().workloadClassName()), e);
        }
        logger.info(String.format("Loaded Workload: %s", workload.getClass().getName()));

        try {
            db = ClassLoaderHelper.loadDb(controlService.configuration().dbClassName());
            db.init(controlService.configuration().asMap());
        } catch (DbException e) {
            throw new ClientException(String.format("Error loading DB class: %s", controlService.configuration().dbClassName()), e);
        }
        logger.info(String.format("Loaded DB: %s", db.getClass().getName()));

        ConcurrentErrorReporter errorReporter = new ConcurrentErrorReporter();

        try {
            completionTimeService = new ThreadedQueuedConcurrentCompletionTimeService(controlService.configuration().peerIds(), errorReporter);
            completionTimeService.submitInitiatedTime(controlService.workloadStartTime());
            completionTimeService.submitCompletedTime(controlService.workloadStartTime());
            for (String peerId : controlService.configuration().peerIds()) {
                completionTimeService.submitExternalCompletionTime(peerId, controlService.workloadStartTime());
            }
            // Wait for workloadStartTime to be applied
            Future<Time> globalCompletionTimeFuture = completionTimeService.globalCompletionTimeFuture();
            while (false == globalCompletionTimeFuture.isDone()) {
                if (errorReporter.errorEncountered())
                    throw new WorkloadException(String.format("Encountered error while waiting for GCT to initialize. Driver terminating.\n%s", errorReporter.toString()));
            }
            if (false == globalCompletionTimeFuture.get().equals(controlService.workloadStartTime())) {
                throw new WorkloadException("Completion Time future failed to return expected value");
            }
        } catch (Exception e) {
            throw new ClientException(
                    String.format("Error while instantiating Completion Time Service with peer IDs %s", controlService.configuration().peerIds().toString()), e);
        }

        metricsService = new ThreadedQueuedConcurrentMetricsService(errorReporter, controlService.configuration().timeUnit());
        GeneratorFactory generators = new GeneratorFactory(new RandomDataGeneratorFactory(RANDOM_SEED));

        logger.info(String.format("Instantiating %s", WorkloadRunner.class.getSimpleName()));
        try {
            Iterator<Operation<?>> operations = workload.operations(generators);
            Iterator<Operation<?>> timeMappedOperations = generators.timeOffsetAndCompress(
                    operations,
                    controlService.workloadStartTime(),
                    controlService.configuration().timeCompressionRatio());

            workloadRunner = new WorkloadRunner(
                    controlService,
                    db,
                    timeMappedOperations,
                    workload.operationClassifications(),
                    metricsService,
                    errorReporter,
                    completionTimeService);
        } catch (WorkloadException e) {
            throw new ClientException(String.format("Error instantiating %s", WorkloadRunner.class.getSimpleName()), e);
        }
        logger.info(String.format("Instantiated %s - Starting Benchmark (%s operations)", WorkloadRunner.class.getSimpleName(), controlService.configuration().operationCount()));

        logger.info("LDBC Workload Driver");
        logger.info(controlService.toString());
    }

    public void start() throws ClientException {
        try {
            workloadRunner.executeWorkload();
        } catch (WorkloadException e) {
            throw new ClientException("Error running Workload", e);
        }

        logger.info("Cleaning up Workload...");
        try {
            workload.cleanup();
        } catch (WorkloadException e) {
            String errMsg = "Error during Workload cleanup";
            throw new ClientException(errMsg, e);
        }

        logger.info("Cleaning up DB...");
        try {
            db.cleanup();
        } catch (DbException e) {
            throw new ClientException("Error during DB cleanup", e);
        }

        logger.info("Shutting down completion time service...");
        try {
            completionTimeService.shutdown();
        } catch (CompletionTimeException e) {
            throw new ClientException("Error during shutdown of completion time service", e);
        }

        logger.info("Shutting down metrics collection service...");
        WorkloadResultsSnapshot workloadResults;
        try {
            workloadResults = metricsService.results();
            metricsService.shutdown();
        } catch (MetricsCollectionException e) {
            throw new ClientException("Error during shutdown of metrics collection service", e);
        }

        logger.info(String.format("Runtime: %s (s)", workloadResults.totalRunDuration().asSeconds()));

        logger.info("Exporting workload metrics...");
        try {
            MetricsManager.export(workloadResults, new SimpleOperationMetricsFormatter(), System.out, MetricsManager.DEFAULT_CHARSET);
            if (null != controlService.configuration().resultFilePath()) {
                File resultFile = new File(controlService.configuration().resultFilePath());
                MetricsManager.export(workloadResults, new JsonOperationMetricsFormatter(), new FileOutputStream(resultFile), MetricsManager.DEFAULT_CHARSET);
            }
            controlService.shutdown();
        } catch (MetricsCollectionException e) {
            throw new ClientException("Could not export workload metrics", e);
        } catch (FileNotFoundException e) {
            throw new ClientException(
                    String.format("Error encountered while trying to write result file: %s", controlService.configuration().resultFilePath()), e);
        }
    }
}