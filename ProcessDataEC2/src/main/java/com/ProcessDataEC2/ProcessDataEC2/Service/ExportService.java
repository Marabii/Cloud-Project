package com.ProcessDataEC2.ProcessDataEC2.Service;

import io.awspring.cloud.sqs.annotation.SqsListener;
import org.apache.commons.csv.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ExportService {

    private static final Logger logger = LoggerFactory.getLogger(ExportService.class);

    @Value("${app.s3.bucketName}")
    private String bucketName;

    @Value("${app.s3.finalDataKey}")
    private String finalDataKey;

    private final S3Client s3Client;
    private final SqsAsyncClient sqsAsyncClient;

    public ExportService(S3Client s3Client, SqsAsyncClient sqsAsyncClient) {
        this.s3Client = s3Client;
        this.sqsAsyncClient = sqsAsyncClient;
    }

    /**
     * This method listens on the Consolidate -> Export queue.
     * message format: srcIp,dstIp,avgFlowDuration,stdDevFlowDuration,avgTotFwdPkts,stdDevTotFwdPkts,trafficNumber
     */
    @SqsListener("https://sqs.us-east-1.amazonaws.com/816069142521/SQS_ConsolidateToExport")
    public void handleExport(String messageBody) {
        logger.info("Received Export message: {}", messageBody);

        try {
            String[] parts = messageBody.split(",");
            if (parts.length != 7) {
                logger.warn("Invalid message format for Export: {}", messageBody);
                return;
            }

            String srcIp = parts[0];
            String dstIp = parts[1];
            double avgFlowDuration = Double.parseDouble(parts[2]);
            double stdDevFlowDuration = Double.parseDouble(parts[3]);
            double avgTotFwdPkts = Double.parseDouble(parts[4]);
            double stdDevTotFwdPkts = Double.parseDouble(parts[5]);
            int trafficNumber = Integer.parseInt(parts[6]);

            // 1. Fetch existing finalData
            logger.debug("Loading existing finalData.csv from S3 (bucket={}, key={})", bucketName, finalDataKey);
            Map<String, FinalData> finalDataMap = fetchFinalData();

            // 2. Update or add the new record
            String keyPair = srcIp + "," + dstIp;
            FinalData data = new FinalData(
                    avgFlowDuration,
                    stdDevFlowDuration,
                    avgTotFwdPkts,
                    stdDevTotFwdPkts,
                    trafficNumber
            );
            logger.debug("Updating keyPair={} with: {}", keyPair, data);
            finalDataMap.put(keyPair, data);

            // 3. Write finalData back to S3
            writeFinalData(finalDataMap);
            logger.info("Updated finalData.csv in S3 with message: {}", messageBody);

        } catch (Exception e) {
            logger.error("Error in ExportService: {}", e.getMessage(), e);
        }
    }

    private Map<String, FinalData> fetchFinalData() {
        Map<String, FinalData> finalDataMap = new HashMap<>();
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(finalDataKey)
                    .build();

            ResponseInputStream<GetObjectResponse> resp = s3Client.getObject(getObjectRequest);
            BufferedReader reader = new BufferedReader(new InputStreamReader(resp));

            CSVParser parser = CSVFormat.DEFAULT
                    .withFirstRecordAsHeader()
                    .parse(reader);

            for (CSVRecord record : parser) {
                String srcIp = record.get("Src IP");
                String dstIp = record.get("Dst IP");
                double avgFlowDuration = Double.parseDouble(record.get("Avg Flow Duration"));
                double stdDevFlowDuration = Double.parseDouble(record.get("StdDev Flow Duration"));
                double avgTotFwdPkts = Double.parseDouble(record.get("Avg Tot Fwd Pkts"));
                double stdDevTotFwdPkts = Double.parseDouble(record.get("StdDev Tot Fwd Pkts"));
                int trafficNumber = Integer.parseInt(record.get("TrafficNumber"));

                String keyPair = srcIp + "," + dstIp;
                finalDataMap.put(keyPair, new FinalData(
                        avgFlowDuration,
                        stdDevFlowDuration,
                        avgTotFwdPkts,
                        stdDevTotFwdPkts,
                        trafficNumber
                ));
            }
            logger.info("Loaded {} records from existing finalData.csv", finalDataMap.size());

        } catch (NoSuchKeyException e) {
            // If finalData.csv doesn't exist, we start fresh
            logger.warn("No existing finalData.csv found, will create a new one.", e);
        } catch (Exception e) {
            logger.error("Error reading finalData.csv: {}", e.getMessage(), e);
        }
        return finalDataMap;
    }

    private void writeFinalData(Map<String, FinalData> finalDataMap) {
        logger.debug("Writing finalData.csv with {} total entries.", finalDataMap.size());
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             PrintWriter writer = new PrintWriter(baos);
             CSVPrinter csvPrinter = new CSVPrinter(writer, CSVFormat.DEFAULT
                     .withHeader("Src IP", "Dst IP", "Avg Flow Duration",
                                 "StdDev Flow Duration", "Avg Tot Fwd Pkts",
                                 "StdDev Tot Fwd Pkts", "TrafficNumber"))) {

            for (Map.Entry<String, FinalData> entry : finalDataMap.entrySet()) {
                String[] ips = entry.getKey().split(",");
                FinalData data = entry.getValue();
                csvPrinter.printRecord(
                        ips[0],
                        ips[1],
                        data.avgFlowDuration,
                        data.stdDevFlowDuration,
                        data.avgTotFwdPkts,
                        data.stdDevTotFwdPkts,
                        data.trafficNumber
                );
            }
            csvPrinter.flush();

            // Upload to S3
            PutObjectRequest putReq = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(finalDataKey)
                    .build();

            s3Client.putObject(putReq, RequestBody.fromBytes(baos.toByteArray()));
            logger.info("Successfully uploaded finalData.csv to s3://{}/{}", bucketName, finalDataKey);

        } catch (Exception e) {
            logger.error("Error writing finalData.csv: {}", e.getMessage(), e);
        }
    }

    private static class FinalData {
        double avgFlowDuration;
        double stdDevFlowDuration;
        double avgTotFwdPkts;
        double stdDevTotFwdPkts;
        int trafficNumber;

        public FinalData(double avgFlowDuration, double stdDevFlowDuration,
                         double avgTotFwdPkts, double stdDevTotFwdPkts,
                         int trafficNumber) {
            this.avgFlowDuration = avgFlowDuration;
            this.stdDevFlowDuration = stdDevFlowDuration;
            this.avgTotFwdPkts = avgTotFwdPkts;
            this.stdDevTotFwdPkts = stdDevTotFwdPkts;
            this.trafficNumber = trafficNumber;
        }

        @Override
        public String toString() {
            return String.format("{avgFlow=%.2f, stdDevFlow=%.2f, avgFwdPkts=%.2f, stdDevPkts=%.2f, trafficNum=%d}",
                    avgFlowDuration, stdDevFlowDuration, avgTotFwdPkts, stdDevTotFwdPkts, trafficNumber);
        }
    }
}
