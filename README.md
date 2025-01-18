Here is the updated **README** file with your clarification:

---

# **README - IoT Traffic Data Processing using AWS**

## **Overview**

This project processes and consolidates IoT traffic data from geographically distributed branches of a multinational enterprise using **AWS Cloud Services**. The solution includes four main components:

- **Upload Client**: Uploads IoT traffic CSV files to Amazon S3.
- **Summarize Worker**: Summarizes traffic data (Flow Duration and Forward Packets) for each IP combination.
- **Consolidator Worker**: Updates statistics (average, standard deviation) for traffic data.
- **Export Client**: Exports consolidated data into a final CSV file in S3.

The project leverages **AWS Lambda**, **Amazon EC2**, **S3**, **SNS**, and **SQS** for processing and communication.

---

## **Prerequisites**

To run the project, you need the following tools and services set up in your environment:

- **Java 17**: Install Java 17 on your local machine and EC2 instances.
- **Apache Maven 3.9.5+**: Used for building and packaging the Java application.
- **AWS SDK for Java 2.x**: For interacting with AWS services like S3, SQS, and SNS.
- **AWS Account**: Set up with access to the required AWS services (Lambda, EC2, S3, SQS, SNS).

### **Install Java 17**

For Amazon EC2 instances:

```bash
$ sudo dnf install java-17-amazon-corretto
$ sudo dnf install java-17-amazon-corretto-devel
```

### **Install Maven**

```bash
$ sudo apt-get install maven
```

### **Set Up AWS SDK for Java**

Add the AWS SDK dependencies to your `pom.xml` file for interaction with AWS services.

```xml
<dependencies>
  <dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>s3</artifactId>
    <version>2.x.x</version>
  </dependency>
  <dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>sqs</artifactId>
    <version>2.x.x</version>
  </dependency>
  <dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>sns</artifactId>
    <version>2.x.x</version>
  </dependency>
</dependencies>
```

---

## **Project Structure**

```bash
|-- src
|   |-- main
|       |-- java
|           |-- com
|               |-- iotdata
|                   |-- UploadClient.java
|                   |-- SummarizeWorker.java
|                   |-- ConsolidatorWorker.java
|                   |-- ExportClient.java
|-- pom.xml (Maven build configuration)
```

---

## **Setup and Run Instructions**

### **1. Building the Project**

1. Clone the repository to your local machine.

2. Navigate to the project directory and build the project using **Maven**:

   ```bash
   $ mvn clean install
   ```

   This will create a `.jar` file under the `target/` directory.

### **2. Upload Client (Java Application)**

The **Upload Client** uploads CSV files to Amazon S3. It is implemented as a Spring Boot application.

**Steps to run the Upload Client on an EC2 instance:**

1. SSH into your EC2 instance:

   ```bash
   $ ssh -i /path/to/key.pem ec2-user@ec2-public-ip
   ```

2. Navigate to the project directory on the EC2 instance.

3. Run the **Upload Client** using **Maven** and Spring Boot:

   ```bash
   $ mvn spring-boot:run
   ```

   This will start the application, and the Upload Client will upload the CSV files to the `unprocessed-data` folder in your S3 bucket.

---

### **3. Summarize Worker (Lambda or Java Application)**

The **Summarize Worker** processes the uploaded CSV files, summarizing the traffic data by calculating **Total Flow Duration** and **Total Forward Packet** for each unique combination of `Src IP` and `Dst IP`.

#### **Lambda Version**:
- Deploy the **Summarize Worker** code as an AWS Lambda function.
- Set the **S3 bucket** as the event source for the Lambda function (triggered when new CSV files are uploaded to `unprocessed-data`).
- The **Lambda function** will be invoked automatically when a new file is uploaded.

#### **Java Application Version**:
To run the **Summarize Worker** as a Java application on **EC2**:

1. SSH into your EC2 instance and install Java 17 if it isn't installed.

2. Transfer the `.jar` file to the EC2 instance:

   ```bash
   $ scp -i /path/to/key.pem target/summarize-worker-1.0-SNAPSHOT.jar ec2-user@ec2-public-ip:/home/ec2-user/
   ```

3. Run the **Summarize Worker**:

   ```bash
   $ java -jar summarize-worker-1.0-SNAPSHOT.jar
   ```

---

### **4. Consolidator Worker (Lambda or Java Application)**

The **Consolidator Worker** processes the summarized data and updates the average and standard deviation values in the `finalData.csv` file stored in the `processed-data` folder of S3.

#### **Lambda Version**:
- Deploy the **Consolidator Worker** code as an AWS Lambda function.
- Set the **SQS queue** as the event source for the Lambda function (triggered when the **Summarize Worker** sends data to the SQS queue).

#### **Java Application Version**:
To run the **Consolidator Worker** as a Java application on **EC2**:

1. Transfer the `.jar` file to the EC2 instance:

   ```bash
   $ scp -i /path/to/key.pem target/consolidator-worker-1.0-SNAPSHOT.jar ec2-user@ec2-public-ip:/home/ec2-user/
   ```

2. Run the **Consolidator Worker**:

   ```bash
   $ java -jar consolidator-worker-1.0-SNAPSHOT.jar
   ```

---

### **5. Export Client (Lambda or Java Application)**

The **Export Client** exports the consolidated data to `finalData.csv` in the `processed-data` folder of S3.

#### **Lambda Version**:
- Deploy the **Export Client** code as an AWS Lambda function.
- Set the **SQS queue** as the event source for the Lambda function (triggered when the **Consolidator Worker** sends data to the SQS queue).

#### **Java Application Version**:
To run the **Export Client** as a Java application on **EC2**:

1. Transfer the `.jar` file to the EC2 instance:

   ```bash
   $ scp -i /path/to/key.pem target/export-client-1.0-SNAPSHOT.jar ec2-user@ec2-public-ip:/home/ec2-user/
   ```

2. Run the **Export Client**:

   ```bash
   $ java -jar export-client-1.0-SNAPSHOT.jar
   ```

---

## **6. Cloud Setup**

### **S3 Buckets**
- Create an **S3 bucket** named `projetcloudiot`.
- Within this bucket, create two folders:
  - `unprocessed-data`: For incoming IoT traffic CSV files.
  - `processed-data`: For the final consolidated data (`finalData.csv`).

### **SNS Topics**
- Set up SNS topics for communication between the workers:
  - One SNS topic to notify the **Summarize Worker** about new uploads.
  - One SNS topic for notifying the **Consolidator Worker** about the summarized data.

### **SQS Queues**
- Create SQS queues to hold messages between the workers:
   - `S3EventNotificationQueue` : SQS queue for transferring data from **S3** to **Summarize Worker**.
  - `SQS_SummarizeToConsolidate` : SQS queue for transferring data from **Summarize Worker** to **Consolidator Worker**.
  - `SQS_ConsolidateToExport` : One SQS queue for transferring data from **Consolidator Worker** to **Export Client**.

---

## **7. Running the Full Pipeline**

1. Upload CSV files using the **Upload Client** (Java app).
2. The **Summarize Worker** (Lambda or EC2) will process the files and send data to SQS.
3. The **Consolidator Worker** will consolidate the statistics and send the updated data to another SQS queue.
4. The **Export Client** will take the final data from SQS and export it to `finalData.csv` in the `processed-data` folder in S3.

---

## **8. Troubleshooting**

- **Lambda Timeout**: If a Lambda function times out, increase the function timeout limit in the AWS console.
- **File Upload Issues**: Ensure the Upload Client has the correct AWS credentials and access to the S3 bucket.
- **SQS Delays**: Check the SQS settings and ensure the messages are being processed in the correct order.

---

## **9. Conclusion**

This README file provides detailed instructions for setting up and running the IoT Traffic Data Processing system using AWS services. By following the steps above, you can successfully upload, process, and export IoT data using a serverless architecture.

---
