package com.conveyal.taui.analysis;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.conveyal.r5.analyst.broker.JobStatus;
import com.conveyal.r5.analyst.cluster.GridRequest;
import com.conveyal.r5.analyst.cluster.GridResultAssembler;
import com.conveyal.r5.analyst.cluster.GridResultConsumer;
import com.conveyal.r5.analyst.scenario.Scenario;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.taui.util.HttpUtil;
import com.conveyal.taui.AnalystConfig;
import com.conveyal.taui.models.Bundle;
import com.conveyal.taui.models.Project;
import com.conveyal.taui.models.RegionalAnalysis;
import com.conveyal.taui.persistence.Persistence;
import com.conveyal.taui.util.JsonUtil;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Manages coordination of multipoint runs with the broker.
 */
public class RegionalAnalysisManager {
    private static final Logger LOG = LoggerFactory.getLogger(RegionalAnalysisManager.class);
    private static AmazonS3 s3 = new AmazonS3Client();

    private static ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 20, 90, TimeUnit.SECONDS, new ArrayBlockingQueue<>(512));

    public static Map<String, JobStatus> statusByJob;

    public static final GridResultConsumer consumer;
    public static final String resultsQueueUrl;

    public static final String brokerUrl = AnalystConfig.offline ? "http://localhost:6001" : AnalystConfig.brokerUrl;


    static {
        AmazonSQS sqs = new AmazonSQSClient();
        sqs.setRegion(Region.getRegion(Regions.fromName(AnalystConfig.region)));
        resultsQueueUrl = sqs.getQueueUrl(AnalystConfig.resultsQueue).getQueueUrl();
        consumer = new GridResultConsumer(resultsQueueUrl, AnalystConfig.resultsBucket);

        new Thread(consumer, "queue-consumer").start();
    }

    public static void enqueue (RegionalAnalysis regionalAnalysis) {
        executor.execute(() -> {
            // first save the scenario
            ProfileRequest request = regionalAnalysis.request.clone();
            Scenario scenario = request.scenario;
            request.scenarioId = scenario.id;
            request.scenario = null;

            String fileName = String.format("%s_%s.json", regionalAnalysis.bundleId, scenario.id);
            File cachedScenario = new File(AnalystConfig.localCache, fileName);
            try {
                JsonUtil.objectMapper.writeValue(cachedScenario, scenario);
            } catch (IOException e) {
                LOG.error("Error saving scenario to disk", e);
            }

            if (!AnalystConfig.offline) {
                // upload to S3
                s3.putObject(AnalystConfig.bundleBucket, fileName, cachedScenario);
            }

            Bundle bundle = Persistence.bundles.get(regionalAnalysis.bundleId);
            Project project = Persistence.projects.get(bundle.projectId);

            // now that that's done, make the requests to the broker
            List<GridRequest> requests = new ArrayList<>();

            for (int x = 0; x < regionalAnalysis.width; x++) {
                for (int y = 0; y < regionalAnalysis.height; y++) {
                    GridRequest req = new GridRequest();
                    req.jobId = regionalAnalysis.id;
                    req.graphId = regionalAnalysis.bundleId;
                    req.workerVersion = regionalAnalysis.workerVersion;
                    req.height = regionalAnalysis.height;
                    req.width = regionalAnalysis.width;
                    req.north = regionalAnalysis.north;
                    req.west = regionalAnalysis.west;
                    req.zoom = regionalAnalysis.zoom;
                    req.outputQueue = resultsQueueUrl;
                    req.cutoffMinutes = regionalAnalysis.cutoffMinutes;
                    req.request = request;
                    req.x = x;
                    req.y = y;
                    req.grid = String.format("%s/%s.grid", project.id, regionalAnalysis.grid);
                    requests.add(req);
                }
            }

            consumer.registerJob(requests.get(0));

            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                JsonUtil.objectMapper.writeValue(baos, requests);

                // TODO cluster?
                HttpPost post = new HttpPost(String.format("%s/enqueue/regional", brokerUrl));
                post.setEntity(new ByteArrayEntity(baos.toByteArray()));
                CloseableHttpResponse res = null;

                try {
                    res = HttpUtil.httpClient.execute(post);
                    EntityUtils.consume(res.getEntity());
                } finally {
                    if (res != null) res.close();
                }

            } catch (IOException e) {
                LOG.error("error enqueueing requests", e);
            }
        });
    }

    public static void deleteJob(String jobId) {
        CloseableHttpResponse res = null;
        try {
            HttpDelete del = new HttpDelete(String.format("%s/jobs/%s", brokerUrl, jobId));
            res = HttpUtil.httpClient.execute(del);
            EntityUtils.consume(res.getEntity());
        } catch (IOException e) {
            LOG.error("error deleting job {}", e);
        } finally {
            if (res != null) try {
                res.close();
            } catch (IOException e) {
                LOG.error("error deleting job {}", e);
            }
        }
        // free temp disk space
        consumer.deleteJob(jobId);
    }

    public static RegionalAnalysisStatus getStatus (String jobId) {
        return consumer.assemblers.containsKey(jobId) ? new RegionalAnalysisStatus(consumer.assemblers.get(jobId)) : null;
    }

    public static final class RegionalAnalysisStatus implements Serializable {
        public int total;
        public int complete;

        public RegionalAnalysisStatus () { /* do nothing */ }

        public RegionalAnalysisStatus (GridResultAssembler assembler) {
            total = assembler.nTotal;
            complete = assembler.nComplete;
        }
    }
}