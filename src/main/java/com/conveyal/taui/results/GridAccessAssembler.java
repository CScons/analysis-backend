package com.conveyal.taui.results;

import com.conveyal.r5.analyst.LittleEndianIntOutputStream;
import com.conveyal.r5.analyst.cluster.CombinedWorkResult;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.taui.analysis.broker.Job;
import com.conveyal.taui.controllers.RegionalAnalysisController;
import com.google.common.io.ByteStreams;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.zip.GZIPOutputStream;

import static com.conveyal.r5.common.Util.human;

/**
 * Assemble regional analysis results arriving from workers into one file per regional analysis on the backend.
 *
 * During distributed computation of access to gridded destinations, workers return raw results for single
 * origins to the worker while polling. These results contain one accessibility measurement per origin grid cell.
 * This class assembles these results into a single large file containing a delta-coded version of the same data for
 * all origin points.
 *
 * Access grids look like this:
 * Header (ASCII text "ACCESSGR") (note that this header is eight bytes, so the full grid can be mapped into a
 *   Javascript typed array if desired)
 * Version, 4-byte integer
 * (4 byte int) Web mercator zoom level
 * (4 byte int) west (x) edge of the grid, i.e. how many pixels this grid is east of the left edge of the world
 * (4 byte int) north (y) edge of the grid, i.e. how many pixels this grid is south of the top edge of the world
 * (4 byte int) width of the grid in pixels
 * (4 byte int) height of the grid in pixels
 * (4 byte int) number of values per pixel
 * (repeated 4-byte int) values of each pixel in row major order.
 */
public class GridAccessAssembler extends MultiOriginAssembler {

    public final RegionalTask request;

    /** The version of the access grids we produce */
    public static final int ACCESS_GRID_VERSION = 0;

    /** The offset to get to the data section of the access grid file. */
    public static final long HEADER_LENGTH_BYTES = 9 * Integer.BYTES;

    /**
     * Construct an assembler for a single regional analysis result grid.
     * This also creates the on-disk scratch buffer into which the results from the workers will be accumulated.
     */
    public GridAccessAssembler(Job job, String outputBucket) {
        super(job, outputBucket, job.templateTask.width * job.templateTask.height);
        this.request = job.templateTask;
        LOG.info("Expecting results for regional analysis with width {}, height {}, 1 value per origin.",
                request.width, request.height);
        // TODO combine with superclass
        long outputFileSizeBytes = request.width * request.height * Integer.BYTES;
        LOG.info("Creating temporary file to store regional analysis results, size is {}.",
                human(outputFileSizeBytes, "B"));
        try {
            bufferFile = File.createTempFile(request.jobId, ".access_grid");
            // On unexpected server shutdown, these files should be deleted.
            // We could attempt to recover from shutdowns but that will take a lot of changes and persisted data.
            bufferFile.deleteOnExit();

            // Write the access grid file header
            FileOutputStream fos = new FileOutputStream(bufferFile);
            LittleEndianIntOutputStream data = new LittleEndianIntOutputStream(fos);
            data.writeAscii("ACCESSGR");
            data.writeInt(ACCESS_GRID_VERSION);
            data.writeInt(request.zoom);
            data.writeInt(request.west);
            data.writeInt(request.north);
            data.writeInt(request.width);
            data.writeInt(request.height);
            data.writeInt(1); // Hard-wired to one bootstrap replication
            data.close();

            // We used to fill the file with zeros here, to "overwrite anything that might be in the file already"
            // according to a code comment. However that creates a burst of up to 1GB of disk activity, which exhausts
            // our IOPS budget on cloud servers with network storage. That then causes the server to fall behind in
            // processing incoming results.
            // This is a newly created temp file, so setting it to a larger size should just create a sparse file
            // full of blocks of zeros (at least on Linux, I don't know what it does on Windows).
            this.randomAccessFile = new RandomAccessFile(bufferFile, "rw");
            randomAccessFile.setLength(outputFileSizeBytes); //TODO check if this should include header length
            LOG.info("Created temporary file of {} to accumulate results from workers.", human(randomAccessFile.length(), "B"));
        } catch (Exception e) {
            error = true;
            LOG.error("Exception while creating regional access grid: " + e.toString());
        }
    }

    /**
     * Gzip the access grid and upload it to S3.
     */
    @Override
    protected synchronized void finish () {
        LOG.info("Finished receiving data for regional analysis {}, uploading to S3", request.jobId);
        try {
            File gzippedGridFile = File.createTempFile(request.jobId, ".access_grid.gz");
            randomAccessFile.close();

            // There's probably a more elegant way to do this with NIO and without closing the buffer.
            // That would be Files.copy(File.toPath(),X) or ByteStreams.copy.
            InputStream is = new BufferedInputStream(new FileInputStream(bufferFile));
            OutputStream os = new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(gzippedGridFile)));
            ByteStreams.copy(is, os);
            is.close();
            os.close();

            LOG.info("GZIP compression reduced regional analysis {} from {} to {} ({}x compression)",
                    request.jobId,
                    human(bufferFile.length(), "B"),
                    human(gzippedGridFile.length(), "B"),
                    (double) bufferFile.length() / gzippedGridFile.length()
            );
            // TODO use generic filePersistence instead of specific S3 client
            RegionalAnalysisController.s3.putObject(outputBucket, String.format("%s.access", request.jobId), gzippedGridFile);
            // Clear temporary files off of the disk because the gzipped version is now on S3.
            bufferFile.delete();
            gzippedGridFile.delete();
        } catch (Exception e) {
            LOG.error("Error uploading results of regional analysis {}", request.jobId, e);
        }
    }

    /**
     * Write to the proper subregion of the buffer for this origin.
     * The origins we receive have 2d coordinates.
     * Flatten them to compute file offsets and for the origin checklist.
     */
    private void writeOneValue (int taskNumber, int value) throws IOException {
        long offset = HEADER_LENGTH_BYTES + taskNumber * Integer.BYTES;
        // RandomAccessFile is not threadsafe and multiple threads may call this, so the actual writing is synchronized.
        writeValueAndMarkOriginComplete(taskNumber, offset, value);
    }

    /**
     * Process a single result.
     * We have bootstrap replications turned off, so there should be only one accessibility result per origin
     * and no delta coding is necessary anymore within each origin.
     * We are also iterating over three dimensions (pointsets, percentiles, cutoffs) but those should produce completely
     * separate access grid files, and are all size 1 for now anyway.
     */
    @Override
    public void handleMessage (CombinedWorkResult workResult) {
        try {
            // Infer x and y cell indexes based on the template task
            int taskNumber = workResult.taskId;

            // Check the dimensions of the result by comparing with fields of this.request
            int nGrids = 1;
            int nPercentiles = 1;
            int nCutoffs = 1;

            // Drop work results for this particular origin into a little-endian output file.
            // We only have one file for now because only one pointset, percentile, and cutoff value.
            checkDimension(workResult, "destination pointsets", workResult.accessibilityValues.length, nGrids);
            for (int[][] gridResult : workResult.accessibilityValues) {
                checkDimension(workResult, "percentiles", gridResult.length, nPercentiles);
                for (int[] percentileResult : gridResult) {
                    checkDimension(workResult, "cutoffs", percentileResult.length, nCutoffs);
                    for (int accessibilityForCutoff : percentileResult) {
                        writeOneValue(taskNumber, accessibilityForCutoff);
                    }
                }
            }
            // TODO It might be more reliable to double-check the bitset of received results inside finish() instead of just counting.
            // FIXME isn't this leaving the files around and the assemblers in memory if the job errors out?
            if (nComplete == nTotal && !error) finish();
        } catch (Exception e) {
            error = true; // the file is garbage TODO better resilience, tell the UI, transmit all errors.
            LOG.error("Error assembling results for query {}", request.jobId, e);
        }
    }
}