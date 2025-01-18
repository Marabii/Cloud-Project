package com.cloudProject.CloudProject.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.regions.Region;

import java.io.IOException;

@Service
@RequiredArgsConstructor
@Slf4j
public class S3Service {

    private final S3Client s3Client;

    @Value("${aws.s3.bucket-name}")
    private String bucketName;

    @Value("${aws.s3.unprocessed-folder}") // Default to 'uploads/' if not specified
    private String uploadFolder;

    /**
     * Uploads a CSV file to the specified S3 bucket.
     *
     * @param file The CSV file to upload.
     * @return The S3 object URL upon successful upload.
     * @throws IllegalArgumentException If the file is invalid.
     * @throws S3Exception              If there's an error with S3 operations.
     */
    public String uploadCsvFile(MultipartFile file) {
        validateFile(file);

        String fileName = generateFileName(file);
        String s3Key = uploadFolder + fileName;

        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .contentLength(file.getSize())
                    .build();

            s3Client.putObject(putObjectRequest, software.amazon.awssdk.core.sync.RequestBody.fromBytes(file.getBytes()));
            log.info("Successfully uploaded file {} to s3://{}/{}", fileName, bucketName, uploadFolder);

            return getS3ObjectUrl(s3Key);
        } catch (S3Exception e) {
            log.error("S3Exception while uploading file {}: {}", fileName, e.awsErrorDetails().errorMessage(), e);
            throw e;
        } catch (IOException e) {
            log.error("IOException while reading file {}: {}", fileName, e.getMessage(), e);
            throw new RuntimeException("Failed to read the file.", e);
        } catch (Exception e) {
            log.error("Unexpected error while uploading file {}: {}", fileName, e.getMessage(), e);
            throw new RuntimeException("Failed to upload the file.", e);
        }
    }

    /**
     * Validates the uploaded file.
     *
     * @param file The file to validate.
     */
    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty.");
        }

        if (!"text/csv".equalsIgnoreCase(file.getContentType()) &&
            !file.getOriginalFilename().toLowerCase().endsWith(".csv")) {
            throw new IllegalArgumentException("Only CSV files are allowed.");
        }

        // Optionally, add more validations (e.g., file size limits)
    }

    /**
     * Generates a unique file name to prevent overwriting existing files.
     *
     * @param file The original file.
     * @return A unique file name.
     */
    private String generateFileName(MultipartFile file) {
        String originalFileName = file.getOriginalFilename();
        String uniqueSuffix = "_" + System.currentTimeMillis();
        if (originalFileName != null && originalFileName.contains(".")) {
            String name = originalFileName.substring(0, originalFileName.lastIndexOf('.'));
            String extension = originalFileName.substring(originalFileName.lastIndexOf('.'));
            return name + uniqueSuffix + extension;
        } else {
            return "upload" + uniqueSuffix + ".csv";
        }
    }

    /**
     * Constructs the S3 object URL.
     *
     * @param key The S3 object key.
     * @return The URL of the uploaded object.
     */
    private String getS3ObjectUrl(String key) {
        return String.format("https://%s.s3.%s.amazonaws.com/%s", bucketName, Region.US_EAST_1, key);
    }
}
