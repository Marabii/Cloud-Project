package com.cloudProject.CloudProject.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled; // Import Scheduled
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import com.cloudProject.CloudProject.Model.DatasetResponse;
import com.cloudProject.CloudProject.Model.ResourceItem;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.core.sync.RequestBody;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.URL;

@Service
@RequiredArgsConstructor
@Slf4j
public class VarioDataService {

    private final S3Client s3Client;

    @Value("${aws.s3.bucket-name}")
    private String bucketName;

    @Value("${aws.s3.unprocessed-folder}")
    private String unprocessedFolder;

    @Value("${dataset.api.url}")
    private String datasetApiUrl;

    // Schedule the method to run every minute
    @Scheduled(fixedRate = 60000) // 60000 milliseconds = 1 minute
    public void fetchAndUploadCsvFiles() {
        log.info("Starting fetchAndUploadCsvFiles task.");

        // Create a WebClient with an increased buffer size limit
        WebClient webClient = WebClient.builder()
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(configurer -> configurer
                                .defaultCodecs()
                                .maxInMemorySize(10 * 1024 * 1024) // Set limit to 10MB
                        )
                        .build())
                .build();

        // 1. Call the external API to get the JSON response
        DatasetResponse datasetResponse = webClient
                .get()
                .uri(datasetApiUrl)
                .retrieve()
                .bodyToMono(DatasetResponse.class)
                .block(); // blocking for simplicity in this example

        if (datasetResponse == null || datasetResponse.getResources() == null) {
            log.error("No resources found in the dataset API response.");
            return;
        }

        // 2. Iterate over each resource
        for (ResourceItem resourceItem : datasetResponse.getResources()) {
            // Check if it's a CSV by either format or filetype. Here we check `format`.
            if ("csv".equalsIgnoreCase(resourceItem.getFormat())) {
                // 3. Download the CSV from the resource URL
                downloadAndUploadToS3(resourceItem);
            }
        }

        log.info("Completed fetchAndUploadCsvFiles task successfully.");
    }

    private void downloadAndUploadToS3(ResourceItem resourceItem) {
        String fileUrl = resourceItem.getUrl();
        String fileName = resourceItem.getTitle();
        if (fileName == null || fileName.isBlank()) {
            // Fallback name if title is missing
            fileName = "unnamed-" + resourceItem.getId() + ".csv";
        }

        try (BufferedInputStream bis = new BufferedInputStream(new URL(fileUrl).openStream())) {
            log.info("Downloading file from URL: {}", fileUrl);

            // 4. Prepare the PUT request
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(unprocessedFolder + fileName)
                    .build();

            // 5. Upload the file to S3
            s3Client.putObject(
                    putObjectRequest,
                    RequestBody.fromInputStream(bis, bis.available() > 0 ? bis.available() : 4096));
            log.info("Successfully uploaded file {} to s3://{}/{}", fileName, bucketName, unprocessedFolder);
        } catch (IOException e) {
            log.error("Error downloading/uploading file: " + fileUrl, e);
        }
    }
}
