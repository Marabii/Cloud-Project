package com.example.cloudworkers.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;  
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.regions.Region;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

public class ExportClientLambda implements RequestHandler<SQSEvent, String> {
    private static final String BUCKET_NAME = "projetcloudiot";
    private static final String FINAL_DATA_KEY = "processed-data/finalData.csv";
    private static final String SQS_QUEUE_URL_INPUT = "https://sqs.us-east-1.amazonaws.com/816069142521/SQS_ConsolidateToExport"; // Replace with your SQS Queue URL

    private final S3Client s3 = S3Client.builder()
            .region(Region.US_EAST_1) // Change to your region
            .build();

    private final SqsClient sqs = SqsClient.builder()
            .region(Region.US_EAST_1) // Change to your region
            .build();

    @Override
    public String handleRequest(SQSEvent event, Context context) {
        for (SQSEvent.SQSMessage msg : event.getRecords()) {
            String body = msg.getBody();
            context.getLogger().log("Processing message: " + body);

            try {
                processMessage(body, context);
                deleteMessage(msg, context);
                context.getLogger().log("Processed and deleted message: " + body);
            } catch (Exception e) {
                context.getLogger().log("Error processing message: " + e.getMessage());
                // Optionally, handle retries or dead-letter queues
            }
        }
        return "Success";
    }

    public void processMessage(String messageBody, Context context) {
        try {
            // Message format: SrcIP,DstIP,AvgFlowDuration,StdDevFlowDuration,AvgTotFwdPkts,StdDevTotFwdPkts,TrafficNumber
            String[] parts = messageBody.split(",");
            if (parts.length != 7) {
                context.getLogger().log("Invalid message format: " + messageBody);
                return;
            }

            String srcIp = parts[0];
            String dstIp = parts[1];
            double avgFlowDuration = Double.parseDouble(parts[2]);
            double stdDevFlowDuration = Double.parseDouble(parts[3]);
            double avgTotFwdPkts = Double.parseDouble(parts[4]);
            double stdDevTotFwdPkts = Double.parseDouble(parts[5]);
            int trafficNumber = Integer.parseInt(parts[6]);

            String keyPair = srcIp + "," + dstIp;

            // Fetch existing finalData.csv
            Map<String, FinalData> finalDataMap = fetchFinalData(context);

            // Update or add the FinalData entry
            FinalData data = new FinalData(avgFlowDuration, stdDevFlowDuration, avgTotFwdPkts, stdDevTotFwdPkts, trafficNumber);
            finalDataMap.put(keyPair, data);

            // Write updated finalData.csv back to S3
            writeFinalData(finalDataMap, context);

        } catch (Exception e) {
            context.getLogger().log("Error processing message: " + e.getMessage());
        }
    }

    public Map<String, FinalData> fetchFinalData(Context context) {
        Map<String, FinalData> finalDataMap = new HashMap<>();

        try {
            GetObjectRequest getFinal = GetObjectRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(FINAL_DATA_KEY)
                    .build();

            ResponseInputStream<GetObjectResponse> finalDataObj = s3.getObject(getFinal);
            BufferedReader reader = new BufferedReader(new InputStreamReader(finalDataObj));
            CSVParser csvParser = CSVFormat.DEFAULT
                    .withFirstRecordAsHeader()
                    .parse(reader);

            for (CSVRecord record : csvParser) {
                String srcIp = record.get("Src IP");
                String dstIp = record.get("Dst IP");
                double avgFlowDuration = Double.parseDouble(record.get("Avg Flow Duration"));
                double stdDevFlowDuration = Double.parseDouble(record.get("StdDev Flow Duration"));
                double avgTotFwdPkts = Double.parseDouble(record.get("Avg Tot Fwd Pkts"));
                double stdDevTotFwdPkts = Double.parseDouble(record.get("StdDev Tot Fwd Pkts"));
                int trafficNumber = Integer.parseInt(record.get("TrafficNumber"));

                String keyPair = srcIp + "," + dstIp;
                finalDataMap.put(keyPair, new FinalData(avgFlowDuration, stdDevFlowDuration, avgTotFwdPkts, stdDevTotFwdPkts, trafficNumber));
            }

        } catch (NoSuchKeyException e) {
            // finalData.csv does not exist yet. Initialize empty map.
            context.getLogger().log("finalData.csv does not exist. Initializing with empty data.");
        } catch (Exception e) {
            context.getLogger().log("Error fetching finalData.csv: " + e.getMessage());
        }

        return finalDataMap;
    }

    public void writeFinalData(Map<String, FinalData> finalDataMap, Context context) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            CSVPrinter csvPrinter = new CSVPrinter(new PrintWriter(outputStream), CSVFormat.DEFAULT
                    .withHeader("Src IP", "Dst IP", "Avg Flow Duration", "StdDev Flow Duration", "Avg Tot Fwd Pkts", "StdDev Tot Fwd Pkts", "TrafficNumber"));

            for (Map.Entry<String, FinalData> entry : finalDataMap.entrySet()) {
                String[] ips = entry.getKey().split(",");
                FinalData data = entry.getValue();
                csvPrinter.printRecord(ips[0], ips[1], data.avgFlowDuration, data.stdDevFlowDuration,
                        data.avgTotFwdPkts, data.stdDevTotFwdPkts, data.trafficNumber);
            }
            csvPrinter.flush();

            PutObjectRequest putFinal = PutObjectRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(FINAL_DATA_KEY)
                    .build();

            s3.putObject(putFinal, RequestBody.fromBytes(outputStream.toByteArray()));
            context.getLogger().log("Uploaded updated finalData.csv to S3.");

        } catch (Exception e) {
            context.getLogger().log("Error writing finalData.csv: " + e.getMessage());
        }
    }

    public void deleteMessage(SQSEvent.SQSMessage msg, Context context) {
        try {
            String receiptHandle = msg.getReceiptHandle();
            DeleteMessageRequest deleteReq = DeleteMessageRequest.builder()
                    .queueUrl(SQS_QUEUE_URL_INPUT)
                    .receiptHandle(receiptHandle)
                    .build();

            sqs.deleteMessage(deleteReq);
            context.getLogger().log("Deleted message from input SQS.");
        } catch (Exception e) {
            context.getLogger().log("Error deleting message from input SQS: " + e.getMessage());
        }
    }

    private static class FinalData {
        double avgFlowDuration;
        double stdDevFlowDuration;
        double avgTotFwdPkts;
        double stdDevTotFwdPkts;
        int trafficNumber;

        FinalData(double avgFlowDuration, double stdDevFlowDuration, double avgTotFwdPkts,
                  double stdDevTotFwdPkts, int trafficNumber) {
            this.avgFlowDuration = avgFlowDuration;
            this.stdDevFlowDuration = stdDevFlowDuration;
            this.avgTotFwdPkts = avgTotFwdPkts;
            this.stdDevTotFwdPkts = stdDevTotFwdPkts;
            this.trafficNumber = trafficNumber;
        }
    }
}
