package com.cloudProject.CloudProject.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
public class S3Config {

    @Value("${aws_access_key_id}")
    private String accessKeyId;

    @Value("${aws_secret_access_key}")
    private String secretAccessKey;

    // If you’re storing the region in application.properties, inject it:
    // e.g., aws.region=us-east-1
    @Value("${aws.region:us-east-1}")  // default to us-east-1 if not specified
    private String region;

    @Bean
    public S3Client s3Client() {
        // 1. Validate that you actually have non-empty credentials:
        if (accessKeyId == null || accessKeyId.isBlank() ||
            secretAccessKey == null || secretAccessKey.isBlank()) {
            throw new IllegalArgumentException("AWS credentials not provided or are empty. Check your properties.");
        }

        AwsBasicCredentials awsCreds = AwsBasicCredentials.create(accessKeyId, secretAccessKey);

        // 2. Build S3Client using the correct region:
        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(awsCreds))
                .build();
    }
}
