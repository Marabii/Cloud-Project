package com.ProcessDataEC2.ProcessDataEC2.Service;

import io.awspring.cloud.sqs.annotation.SqsListener;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

@Service
public class SummarizeService {

    private final S3Client s3Client;
    private final SqsAsyncClient sqsAsyncClient;

    @Value("${app.s3.bucketName}")
    private String bucketName;

    @Value("${app.sqs.summarizeToConsolidateQueue}")
    private String summarizeToConsolidateQueueUrl;

    public SummarizeService(S3Client s3Client, SqsAsyncClient sqsAsyncClient) {
        this.s3Client = s3Client;
        this.sqsAsyncClient = sqsAsyncClient;
    }

    /**
     * This method is triggered by the message from S3 Event (which notifies a new CSV in unprocessed-data/).
     * The message body from S3 notifications will typically include info about the S3 object key.
     */
    @SqsListener(value = "${app.sqs.S3EventNotificationQueue}") 
    public void handleS3Notification(String s3EventMessage) {
        // 1. Parse the S3 Event to get the object key
        //    In a real scenario, you'd parse JSON from the message. 
        //    For a simplified approach, let's assume the message body is just the object key.
        String objectKey = s3EventMessage;
        if (!objectKey.startsWith("unprocessed-data/") || !objectKey.endsWith(".csv")) {
            System.out.println("Ignoring non-target file: " + objectKey);
            return;
        }

        Map<String, Summary> summaryMap = new HashMap<>();
        try {
            // 2. Download the object from S3
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .build();
            ResponseInputStream<GetObjectResponse> s3object = s3Client.getObject(getObjectRequest);

            // 3. Parse CSV
            BufferedReader reader = new BufferedReader(new InputStreamReader(s3object));
            Iterable<CSVRecord> records = CSVFormat.DEFAULT
                    .withFirstRecordAsHeader()
                    .parse(reader);

            // 4. Summarize
            for (CSVRecord record : records) {
                String srcIp = record.get("Src IP");
                String dstIp = record.get("Dst IP");
                long flowDuration = Long.parseLong(record.get("Flow Duration"));
                long totFwdPkts = Long.parseLong(record.get("Tot Fwd Pkts"));

                String keyPair = srcIp + "," + dstIp;
                summaryMap.putIfAbsent(keyPair, new Summary());
                Summary summary = summaryMap.get(keyPair);
                summary.totalFlowDuration += flowDuration;
                summary.totalFwdPkts += totFwdPkts;
            }

            // 5. Send each summarized result to SQS for Consolidation
            for (Map.Entry<String, Summary> entry : summaryMap.entrySet()) {
                String keyPair = entry.getKey();
                Summary summary = entry.getValue();
                String messageBody = keyPair + "," + summary.totalFlowDuration + "," + summary.totalFwdPkts;

                sqsAsyncClient.sendMessage(SendMessageRequest.builder()
                        .queueUrl(summarizeToConsolidateQueueUrl)
                        .messageBody(messageBody)
                        .build());

                System.out.println("Sent message to SummarizeToConsolidate queue: " + messageBody);
            }

        } catch (Exception e) {
            System.err.println("Error processing file: " + objectKey + ": " + e.getMessage());
        }
    }

    private static class Summary {
        long totalFlowDuration = 0;
        long totalFwdPkts = 0;
    }
}
