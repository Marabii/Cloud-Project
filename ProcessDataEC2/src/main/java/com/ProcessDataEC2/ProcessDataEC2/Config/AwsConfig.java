package com.ProcessDataEC2.ProcessDataEC2.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

@Configuration
public class AwsConfig {

    @Value("${spring.cloud.aws.region.static}")
    private String region;

    @Value("${spring.cloud.aws.credentials.access-key}")
    private String accessKeyId;

    @Value("${spring.cloud.aws.credentials.secret-key}")
    private String secretAccessKey;

    @Bean
    public S3Client s3Client() {
        // 1. Validate that you actually have non-empty credentials:
        if (accessKeyId == null || accessKeyId.isBlank() ||
            secretAccessKey == null || secretAccessKey.isBlank()) {
            throw new IllegalArgumentException("AWS credentials not provided or are empty. Check your properties.");
        }

        AwsBasicCredentials awsCreds = AwsBasicCredentials.create(accessKeyId, secretAccessKey);

        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(awsCreds))
                .build(); // Uses default credentials provider chain (includes instance profile)
    }

    @Bean
    public SqsAsyncClient sqsAsyncClient() {
        // 1. Validate that you actually have non-empty credentials:
        if (accessKeyId == null || accessKeyId.isBlank() ||
            secretAccessKey == null || secretAccessKey.isBlank()) {
            throw new IllegalArgumentException("AWS credentials not provided or are empty. Check your properties.");
        }

        AwsBasicCredentials awsCreds = AwsBasicCredentials.create(accessKeyId, secretAccessKey);

        return SqsAsyncClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(awsCreds))
                .build();
    }

}
