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
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
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

    // // Schedule the method to run every minute
    // @Scheduled(fixedRate = 84400000) // 60000 milliseconds = 1 minute
    // public void fetchAndUploadCsvFiles() {
    //     log.info("Starting fetchAndUploadCsvFiles task.");

    //     // Create a WebClient with an increased buffer size limit
    //     WebClient webClient = WebClient.builder()
    //             .exchangeStrategies(ExchangeStrategies.builder()
    //                     .codecs(configurer -> configurer
    //                             .defaultCodecs()
    //                             .maxInMemorySize(30 * 1024 * 1024) // Set limit to 10MB
    //                     )
    //                     .build())
    //             .build();

    //     // 1. Call the external API to get the JSON response
    //     DatasetResponse datasetResponse = webClient
    //             .get()
    //             .uri(datasetApiUrl)
    //             .retrieve()
    //             .bodyToMono(DatasetResponse.class)
    //             .block(); // blocking for simplicity in this example

    //     if (datasetResponse == null || datasetResponse.getResources() == null) {
    //         log.error("No resources found in the dataset API response.");
    //         return;
    //     }

    //     boolean hasNewFiles = false;

    //     // 2. Iterate over each resource
    //     for (ResourceItem resourceItem : datasetResponse.getResources()) {
    //         // Check if it's a CSV by either format or filetype. Here we check `format`.
    //         if ("csv".equalsIgnoreCase(resourceItem.getFormat())) {
    //             String fileName = getFileName(resourceItem);
    //             String s3Key = unprocessedFolder + fileName;

    //             // Check if the file already exists in S3
    //             if (!isFileExistsInS3(s3Key)) {
    //                 hasNewFiles = true;
    //                 break; // Exit early if at least one new file is found
    //             }
    //         }
    //     }

    //     if (!hasNewFiles) {
    //         log.info("No new CSV files to process.");
    //         return;
    //     }

    //     // Since there are new files, proceed to download and upload
    //     for (ResourceItem resourceItem : datasetResponse.getResources()) {
    //         if ("csv".equalsIgnoreCase(resourceItem.getFormat())) {
    //             downloadAndUploadToS3(resourceItem);
    //         }
    //     }

    //     log.info("Completed fetchAndUploadCsvFiles task successfully.");
    // }

    private String getFileName(ResourceItem resourceItem) {
        String fileName = resourceItem.getTitle();
        if (fileName == null || fileName.isBlank()) {
            // Fallback name if title is missing
            fileName = "unnamed-" + resourceItem.getId() + ".csv";
        } else {
            // Ensure the file name ends with .csv
            if (!fileName.toLowerCase().endsWith(".csv")) {
                fileName += ".csv";
            }
        }
        return fileName;
    }

    private boolean isFileExistsInS3(String key) {
        try {
            HeadObjectRequest headObjectRequest = HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();

            HeadObjectResponse headObjectResponse = s3Client.headObject(headObjectRequest);
            log.debug("File {} exists in S3 bucket {}.", key, bucketName);
            return true;
        } catch (software.amazon.awssdk.services.s3.model.NoSuchKeyException e) {
            log.debug("File {} does not exist in S3 bucket {}.", key, bucketName);
            return false;
        } catch (Exception e) {
            log.error("Error checking existence of file {} in S3: {}", key, e.getMessage());
            // Depending on your requirements, you might want to treat this as non-existing
            return false;
        }
    }

    private void downloadAndUploadToS3(ResourceItem resourceItem) {
        String fileUrl = resourceItem.getUrl();
        String fileName = getFileName(resourceItem);
        String s3Key = unprocessedFolder + fileName;

        try (BufferedInputStream bis = new BufferedInputStream(new URL(fileUrl).openStream())) {
            log.info("Downloading file from URL: {}", fileUrl);

            // 4. Prepare the PUT request
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .build();

            // 5. Upload the file to S3
            s3Client.putObject(
                    putObjectRequest,
                    RequestBody.fromInputStream(bis, bis.available() > 0 ? bis.available() : 4096));
            log.info("Successfully uploaded file {} to s3://{}/{}", fileName, bucketName, unprocessedFolder);
        } catch (IOException e) {
            log.error("Error downloading/uploading file: " + fileUrl, e);
        } catch (Exception e) {
            log.error("Unexpected error during upload of file {}: {}", fileName, e.getMessage(), e);
        }
    }
}
