package com.example.cloudworkers.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.regions.Region;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

public class ConsolidatorWorkerLambda implements RequestHandler<SQSEvent, String> {
    private static final String BUCKET_NAME = "projetcloudiot";
    private static final String FINAL_DATA_KEY = "processed-data/finalData.csv";
    private static final String SQS_QUEUE_URL_OUTPUT = "https://sqs.us-east-1.amazonaws.com/816069142521/SQS_ConsolidateToExport";

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
                String calculatedData = processMessage(body, context);
                if (calculatedData != null) {
                    sendMessageToSQS(calculatedData, context);
                    context.getLogger().log("Sent calculated data to output SQS: " + calculatedData);
                }
                // No need to delete the message; Lambda auto-deletes upon successful execution
            } catch (Exception e) {
                context.getLogger().log("Error processing message: " + e.getMessage());
                // Optionally, handle retries or dead-letter queues
            }
        }
        return "Success";
    }

    public String processMessage(String messageBody, Context context) {
        try {
            // Message format: SrcIP,DstIP,TotalFlowDuration,TotalFwdPkts
            String[] parts = messageBody.split(",");
            if (parts.length != 4) {
                context.getLogger().log("Invalid message format: " + messageBody);
                return null;
            }

            String srcIp = parts[0];
            String dstIp = parts[1];
            long totalFlowDuration = Long.parseLong(parts[2]);
            long totalFwdPkts = Long.parseLong(parts[3]);

            String keyPair = srcIp + "," + dstIp;

            // Fetch existing finalData.csv
            Map<String, FinalData> finalDataMap = fetchFinalData(context);

            // Get existing data or initialize with zeros
            FinalData existingData = finalDataMap.getOrDefault(keyPair, new FinalData());

            // Calculate new statistics
            double newAvgFlowDuration = (existingData.avgFlowDuration * existingData.trafficNumber + totalFlowDuration) / (existingData.trafficNumber + 1);
            double newStdDevFlowDuration = Math.abs(totalFlowDuration - newAvgFlowDuration);
            double newAvgTotFwdPkts = (existingData.avgTotFwdPkts * existingData.trafficNumber + totalFwdPkts) / (existingData.trafficNumber + 1);
            double newStdDevTotFwdPkts = Math.abs(totalFwdPkts - newAvgTotFwdPkts);
            int newTrafficNumber = existingData.trafficNumber + 1;

            // Create updated FinalData
            FinalData updatedData = new FinalData(newAvgFlowDuration, newStdDevFlowDuration, newAvgTotFwdPkts, newStdDevTotFwdPkts, newTrafficNumber);

            // Prepare the structured data to send to Export Client
            String calculatedData = String.format("%s,%s,%.2f,%.2f,%.2f,%.2f,%d",
                    srcIp, dstIp, updatedData.avgFlowDuration, updatedData.stdDevFlowDuration,
                    updatedData.avgTotFwdPkts, updatedData.stdDevTotFwdPkts, updatedData.trafficNumber);

            return calculatedData;

        } catch (Exception e) {
            context.getLogger().log("Error processing message: " + e.getMessage());
            return null;
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

    public void sendMessageToSQS(String messageBody, Context context) {
        try {
            SendMessageRequest sendMsgRequest = SendMessageRequest.builder()
                    .queueUrl(SQS_QUEUE_URL_OUTPUT)
                    .messageBody(messageBody)
                    .build();

            sqs.sendMessage(sendMsgRequest);
        } catch (Exception e) {
            context.getLogger().log("Error sending message to output SQS: " + e.getMessage());
        }
    }

    private static class FinalData {
        double avgFlowDuration;
        double stdDevFlowDuration;
        double avgTotFwdPkts;
        double stdDevTotFwdPkts;
        int trafficNumber;

        FinalData() {
            this.avgFlowDuration = 0.0;
            this.stdDevFlowDuration = 0.0;
            this.avgTotFwdPkts = 0.0;
            this.stdDevTotFwdPkts = 0.0;
            this.trafficNumber = 0;
        }

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
