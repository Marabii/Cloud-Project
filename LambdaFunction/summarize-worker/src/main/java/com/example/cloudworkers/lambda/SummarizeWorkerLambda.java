package com.example.cloudworkers.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.S3Event;
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

public class SummarizeWorkerLambda implements RequestHandler<S3Event, String> {
    private static final String BUCKET_NAME = "projetcloudiot";
    private static final String SQS_QUEUE_URL = "https://sqs.us-east-1.amazonaws.com/816069142521/SQS_SummarizeToConsolidate";

    private final S3Client s3 = S3Client.builder()
            .region(Region.US_EAST_1)
            .build();

    private final SqsClient sqs = SqsClient.builder()
            .region(Region.US_EAST_1)
            .build();

    @Override
    public String handleRequest(S3Event event, Context context) {
        String srcBucket = event.getRecords().get(0).getS3().getBucket().getName();
        String objectKey = event.getRecords().get(0).getS3().getObject().getKey();

        context.getLogger().log("Processing file: " + objectKey);

        // Check if the object is in the unprocessed-data/ folder and is a CSV file
        if (!objectKey.startsWith("unprocessed-data/") || !objectKey.endsWith(".csv")) {
            context.getLogger().log("Ignoring non-CSV or non-target folder file: " + objectKey);
            return "Ignored";
        }

        Map<String, Summary> summaryMap = new HashMap<>();

        try {
            GetObjectRequest getObj = GetObjectRequest.builder()
                    .bucket(srcBucket)
                    .key(objectKey)
                    .build();

            ResponseInputStream<GetObjectResponse> s3Object = s3.getObject(getObj);

            BufferedReader reader = new BufferedReader(new InputStreamReader(s3Object));
            Iterable<CSVRecord> records = CSVFormat.DEFAULT
                    .withFirstRecordAsHeader()
                    .parse(reader);

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

            // Send each summary to SQS
            for (Map.Entry<String, Summary> entry : summaryMap.entrySet()) {
                String keyPair = entry.getKey();
                Summary summary = entry.getValue();
                String messageBody = keyPair + "," + summary.totalFlowDuration + "," + summary.totalFwdPkts;
                sendMessageToSQS(messageBody, context);
                context.getLogger().log("Sent summary to SQS: " + messageBody);
            }

            return "Success";
        } catch (Exception e) {
            context.getLogger().log("Error processing file " + objectKey + ": " + e.getMessage());
            return "Error";
        }
    }

    public void sendMessageToSQS(String messageBody, Context context) {
        try {
            SendMessageRequest sendMsgRequest = SendMessageRequest.builder()
                    .queueUrl(SQS_QUEUE_URL)
                    .messageBody(messageBody)
                    .build();

            sqs.sendMessage(sendMsgRequest);
        } catch (Exception e) {
            context.getLogger().log("Error sending message to SQS: " + e.getMessage());
        }
    }

    private static class Summary {
        long totalFlowDuration = 0;
        long totalFwdPkts = 0;
    }
}
