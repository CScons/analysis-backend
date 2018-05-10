package com.conveyal.taui.analysis.broker;

import com.conveyal.r5.analyst.cluster.AnalystWorker;
import com.conveyal.r5.analyst.cluster.JobSimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * This test is not an automatic unit test. It is an integration test that must be started manually, because it takes
 * a long time to run. It will start up a broker and some local workers, then submit a large job to the broker. The
 * workers will fail to complete tasks some percentage of the time, but eventually the whole job should be finished
 * because the broker will redeliver lost tasks to the workers.
 *
 * Progress can be followed with:
 * watch --interval 1 curl http://localhost:9001/jobs
 *
 * FIXME this test needs to be updated to work with the new broker that does not run as a separate thread / process
 */
public class RedeliveryTest {

    private static final Logger LOG = LoggerFactory.getLogger(RedeliveryTest.class);
    static final int N_TASKS = 100;
    static final int N_WORKERS = 4;
    static final int FAILURE_RATE = 20; // percent

    public static void main(String[] params) {

        // Start a broker in a new thread.
        Properties brokerConfig = new Properties();
        brokerConfig.setProperty("graphs-bucket", "FAKE");
        brokerConfig.setProperty("pointsets-bucket", "FAKE");
        brokerConfig.setProperty("work-offline", "true");
        // START BROKER HERE
        // Thread brokerThread = new Thread(null, brokerMain, "BROKER-THREAD"); // TODO combine broker and brokermain, set offline mode.
        // brokerThread.start();

        // Start some workers.
        // Do not set any initial graph, because the workers are only going to simulate doing any work.
        Properties workerConfig = new Properties();
        List<Thread> workerThreads = new ArrayList<>();
        for (int i = 0; i < N_WORKERS; i++) {
            AnalystWorker worker = AnalystWorker.forConfig(workerConfig);
            worker.dryRunFailureRate = FAILURE_RATE;
            Thread workerThread = new Thread(worker);
            workerThreads.add(workerThread);
            workerThread.start();
        }

        // Feed some work to the broker.
        JobSimulator jobSimulatorA = new JobSimulator();
        jobSimulatorA.nOrigins = N_TASKS;
        jobSimulatorA.graphId = "GRAPH";
        jobSimulatorA.sendFakeJob();

        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) { }

        // Feed another job to the broker, to ensure redelivery can deal with multiple jobs.
        JobSimulator jobSimulatorB = new JobSimulator();
        jobSimulatorB.nOrigins = N_TASKS;
        jobSimulatorB.graphId = "GRAPH";
        jobSimulatorB.sendFakeJob();

        // Wait for all tasks to be marked finished
//        while (brokerMain.broker.anyJobsActive()) {
//            try {
//                LOG.info("Some jobs are still not complete.");
//                Thread.sleep(2000);
//            } catch (InterruptedException e) { }
//        }

        LOG.info("All jobs finished.");
        System.exit(0);
    }

}
