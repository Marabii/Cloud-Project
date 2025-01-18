package com.ProcessDataEC2.ProcessDataEC2.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

@Service
public class SummarizeService {

    private static final Logger logger = LoggerFactory.getLogger(SummarizeService.class);

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
     * The message body from S3 notifications is a JSON object containing information about the S3 event.
     */
    @SqsListener("https://sqs.us-east-1.amazonaws.com/816069142521/S3EventNotificationQueue")
    public void handleS3Notification(String s3EventMessage) {
        logger.info("Received S3 Event Notification message:\n{}", s3EventMessage);

        ObjectMapper objectMapper = new ObjectMapper();
        try {
            // Parse the JSON message to extract the object key(s)
            JsonNode root = objectMapper.readTree(s3EventMessage);
            JsonNode records = root.path("Records");
            if (!records.isArray()) {
                logger.warn("Invalid S3 event format or no 'Records' array found. Message:\n{}", s3EventMessage);
                return;
            }

            for (JsonNode record : records) {
                String objectKey = record.path("s3").path("object").path("key").asText();
                if (!objectKey.startsWith("unprocessed-data/") || !objectKey.endsWith(".csv")) {
                    logger.info("Ignoring non-target file: {}", objectKey);
                    continue;
                }
                logger.info("Starting CSV processing for: {}", objectKey);
                processCsvFile(objectKey);
            }

        } catch (Exception e) {
            logger.error("Error parsing or handling S3 event message: {}", e.getMessage(), e);
        }
    }

    private void processCsvFile(String objectKey) {
        Map<String, Summary> summaryMap = new HashMap<>();
        try {
            // 1. Download the object from S3
            logger.debug("Fetching CSV from bucket={} key={}", bucketName, objectKey);
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .build();
            ResponseInputStream<GetObjectResponse> s3Object = s3Client.getObject(getObjectRequest);

            // 2. Parse CSV
            BufferedReader reader = new BufferedReader(new InputStreamReader(s3Object));
            Iterable<CSVRecord> records = CSVFormat.DEFAULT
                    .withFirstRecordAsHeader()
                    .parse(reader);

            logger.debug("Parsing CSV rows for file: {}", objectKey);
            int rowCount = 0;
            // 3. Summarize
            for (CSVRecord record : records) {
                rowCount++;
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
            logger.info("Finished parsing {} rows from file: {}", rowCount, objectKey);

            // 4. Send each summarized result to SQS for Consolidation
            for (Map.Entry<String, Summary> entry : summaryMap.entrySet()) {
                String keyPair = entry.getKey();
                Summary summary = entry.getValue();
                String messageBody = keyPair + "," + summary.totalFlowDuration + "," + summary.totalFwdPkts;

                logger.debug("Sending summarized data to queue={} with body={}", 
                        summarizeToConsolidateQueueUrl, messageBody);

                sqsAsyncClient.sendMessage(SendMessageRequest.builder()
                        .queueUrl(summarizeToConsolidateQueueUrl)
                        .messageBody(messageBody)
                        .build())
                        .whenComplete((resp, err) -> {
                            if (err != null) {
                                logger.error("Failed to send message to SummarizeToConsolidateQueue: {}", err.getMessage(), err);
                            } else {
                                logger.info("Sent message to SummarizeToConsolidateQueue: {}", messageBody);
                            }
                        });
            }

        } catch (Exception e) {
            logger.error("Error processing file={} : {}", objectKey, e.getMessage(), e);
        }
    }

    private static class Summary {
        long totalFlowDuration = 0;
        long totalFwdPkts = 0;
    }
}
