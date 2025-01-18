package com.ProcessDataEC2.ProcessDataEC2.Service;

import io.awspring.cloud.sqs.annotation.SqsListener;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
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

    @SqsListener(value = "${app.sqs.consolidateToExportQueue}")
    public void handleExport(String messageBody) {
        // message format: srcIp,dstIp,avgFlowDuration,stdDevFlowDuration,avgTotFwdPkts,stdDevTotFwdPkts,trafficNumber
        try {
            String[] parts = messageBody.split(",");
            if (parts.length != 7) {
                System.out.println("Invalid message format: " + messageBody);
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
            finalDataMap.put(keyPair, data);

            // 3. Write finalData back to S3
            writeFinalData(finalDataMap);
            System.out.println("Updated finalData.csv in S3 with: " + messageBody);

        } catch (Exception e) {
            System.err.println("Error in ExportService: " + e.getMessage());
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

        } catch (NoSuchKeyException e) {
            // If finalData.csv doesn't exist, we start fresh
            System.out.println("No existing finalData.csv found. Creating new one.");
        } catch (Exception e) {
            System.err.println("Error reading finalData.csv: " + e.getMessage());
        }
        return finalDataMap;
    }

    private void writeFinalData(Map<String, FinalData> finalDataMap) {
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

        } catch (Exception e) {
            System.err.println("Error writing finalData.csv: " + e.getMessage());
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
    }
}
