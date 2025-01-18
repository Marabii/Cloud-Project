package com.ProcessDataEC2.ProcessDataEC2.Service;

import io.awspring.cloud.sqs.annotation.SqsListener;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * This service listens to the Summarize -> Consolidate SQS queue,
 * loads existing finalData, updates or creates a new record with
 * average & std dev computations, then sends to Consolidate->Export queue.
 */
@Service
public class ConsolidateService {

    @Value("${app.s3.bucketName}")
    private String bucketName;

    @Value("${app.s3.finalDataKey}")
    private String finalDataKey;

    @Value("${app.sqs.consolidateToExportQueue}")
    private String consolidateToExportQueueUrl;

    private final S3Client s3Client;
    private final SqsAsyncClient sqsAsyncClient;

    public ConsolidateService(S3Client s3Client, SqsAsyncClient sqsAsyncClient) {
        this.s3Client = s3Client;
        this.sqsAsyncClient = sqsAsyncClient;
    }

    @SqsListener(value = "${app.sqs.summarizeToConsolidateQueue}")
    public void handleConsolidation(String message) {
        // message format: SrcIP,DstIP,TotalFlowDuration,TotalFwdPkts
        // e.g. "192.168.0.1,10.0.0.1,1200,45"
        try {
            String[] parts = message.split(",");
            if (parts.length != 4) {
                System.out.println("Invalid message format: " + message);
                return;
            }

            String srcIp = parts[0];
            String dstIp = parts[1];
            long totalFlowDuration = Long.parseLong(parts[2]);
            long totalFwdPkts = Long.parseLong(parts[3]);

            // 1. Fetch existing finalData from S3
            Map<String, FinalData> finalDataMap = fetchFinalData();

            // 2. Compute new stats
            String keyPair = srcIp + "," + dstIp;
            FinalData existing = finalDataMap.getOrDefault(keyPair, new FinalData());

            double newAvgFlowDuration = (existing.avgFlowDuration * existing.trafficNumber + totalFlowDuration)
                    / (existing.trafficNumber + 1);
            double newStdDevFlowDuration = Math.abs(totalFlowDuration - newAvgFlowDuration); 
            double newAvgTotFwdPkts = (existing.avgTotFwdPkts * existing.trafficNumber + totalFwdPkts)
                    / (existing.trafficNumber + 1);
            double newStdDevTotFwdPkts = Math.abs(totalFwdPkts - newAvgTotFwdPkts);
            int newTrafficNumber = existing.trafficNumber + 1;

            // 3. Build output data
            String calculatedData = String.format("%s,%s,%.2f,%.2f,%.2f,%.2f,%d",
                    srcIp, dstIp,
                    newAvgFlowDuration,
                    newStdDevFlowDuration,
                    newAvgTotFwdPkts,
                    newStdDevTotFwdPkts,
                    newTrafficNumber
            );

            // 4. Send to Consolidate->Export queue
            sqsAsyncClient.sendMessage(
                    SendMessageRequest.builder()
                            .queueUrl(consolidateToExportQueueUrl)
                            .messageBody(calculatedData)
                            .build()
            );

            System.out.println("Sent to Export queue: " + calculatedData);

        } catch (Exception e) {
            System.err.println("Error consolidating message: " + e.getMessage());
        }
    }

    private Map<String, FinalData> fetchFinalData() {
        Map<String, FinalData> finalDataMap = new HashMap<>();
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(finalDataKey)
                    .build();
            ResponseInputStream<GetObjectResponse> finalDataObj = s3Client.getObject(getObjectRequest);
            BufferedReader reader = new BufferedReader(new InputStreamReader(finalDataObj));

            Iterable<CSVRecord> csvRecords = CSVFormat.DEFAULT
                    .withFirstRecordAsHeader()
                    .parse(reader);

            for (CSVRecord record : csvRecords) {
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
        } catch (NoSuchKeyException e) {
            System.out.println("No finalData.csv found, returning empty map.");
        } catch (Exception e) {
            System.err.println("Error fetching finalData.csv: " + e.getMessage());
        }
        return finalDataMap;
    }

    private static class FinalData {
        double avgFlowDuration = 0.0;
        double stdDevFlowDuration = 0.0;
        double avgTotFwdPkts = 0.0;
        double stdDevTotFwdPkts = 0.0;
        int trafficNumber = 0;

        public FinalData() { }

        public FinalData(double avgFlowDuration, double stdDevFlowDuration, double avgTotFwdPkts,
                         double stdDevTotFwdPkts, int trafficNumber) {
            this.avgFlowDuration = avgFlowDuration;
            this.stdDevFlowDuration = stdDevFlowDuration;
            this.avgTotFwdPkts = avgTotFwdPkts;
            this.stdDevTotFwdPkts = stdDevTotFwdPkts;
            this.trafficNumber = trafficNumber;
        }
    }
}
