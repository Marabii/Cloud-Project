# IoT Traffic Data Processing Using AWS Services

## 1. Introduction
This project focuses on processing and summarizing IoT traffic data collected from geographically distributed devices in a multinational enterprise. The goal is to provide a solution for detecting anomalies and bottlenecks in the network traffic, which can guide infrastructure investment decisions. The solution uses AWS Cloud Services to process and manage the data efficiently, reducing the need for a permanent infrastructure.

### Problem Context
- The data is collected in CSV format and uploaded to an S3 bucket for processing.
- Lambda functions are used for serverless, event-driven processing, and EC2 instances provide flexibility for heavier workloads.
- The project architecture ensures scalability and fault tolerance.

---

## 2. Solution Overview
The solution includes several components to handle different aspects of the data pipeline:
- **Upload Client**: Uploads IoT traffic CSV files to an S3 bucket.
- **Summarize Worker**: Processes each CSV file to calculate the total flow duration and total forward packet for every Src IP to Dst IP combination.
- **Consolidator Worker**: Updates statistics (average and standard deviation) for each Src IP to Dst IP combination.
- **Export Client**: Exports the final consolidated data to a CSV file in S3.

All components work in a cloud-native manner, leveraging AWS Lambda functions and EC2 instances for compute, S3 for storage, and SQS for message queuing.

---

## 3. Solution Architecture
The architecture leverages multiple AWS services to handle data efficiently:

### A. AWS Lambda
- **Use**: For serverless processing of CSV files.
- **Components**:
  - Summarize Worker
  - Consolidator Worker
  - Export Client
- **Advantage**: Automatically scales to handle varying traffic loads, and the pay-per-use pricing model helps control costs.

### B. Amazon EC2
- **Use**: As an alternative to Lambda, for running Java-based applications.
- **Components**:
  - Upload Client
  - Summarize Worker
  - Consolidator Worker
  - Export Client
- **Advantage**: Provides full control over resources and execution environments, suitable for more customized or long-running processes.

### C. Amazon S3
- **Use**: The S3 bucket named `projetcloudiot` stores:
  - Initial data in the `unprocessed-data` folder.
  - Processed data in the file `finalData.csv` in the `processed-data` folder.
- **Advantage**: Provides durability, scalability, and cost-efficiency. S3 is the central data store for all files in the pipeline.

### D. Amazon SQS
- **Use**: Acts as an intermediary message queue between workers (Summarize, Consolidator, and Export).
- **Advantage**: Ensures reliable, asynchronous processing, even if a worker is unavailable or busy.

---

## 4. Workflow of Components

### A. Upload Client
- **Function**: Uploads raw IoT traffic data in CSV format to Amazon S3 (`unprocessed-data` folder).
- **Implementation**: A Java application that reads local CSV files and uploads them to S3.
- **Workflow**:
  1. Fetches CSV files from IoT devices across branches via API.
  2. Uploads files to the `unprocessed-data` folder in S3.
  3. Triggers the Summarize Worker for processing.

### B. Summarize Worker
- **Function**: Processes CSV files, calculating:
  - Total Flow Duration (sum of Flow Duration for each combination).
  - Total Forward Packet (sum of Tot Fwd Pkts for each combination).
- **Implementation**:
  - As a Lambda function.
  - As a Java application on EC2.
- **Workflow**:
  1. Listens for S3 event triggers.
  2. Reads and parses CSV files, computes summarized data.
  3. Sends data to the `SQS_SummarizeToConsolidate` queue.

### C. Consolidator Worker
- **Function**: Consolidates statistics, including averages and standard deviations, and updates `finalData.csv`.
- **Implementation**: As a Lambda function and Java application on EC2.
- **Workflow**:
  1. Receives summarized data from the SQS queue.
  2. Checks for existing Src IP and Dst IP combinations in `finalData.csv`.
     - **If exists**:
       - Updates averages, standard deviations, and increments TrafficNumber.
     - **If new**:
       - Initializes statistics and sets TrafficNumber to 1.
  3. Sends updated data to the `SQS_ConsolidateToExport` queue.

### D. Export Client
- **Function**: Exports consolidated data into `finalData.csv` in the `processed-data` folder in S3.
- **Implementation**: As a Lambda function and Java application on EC2.
- **Workflow**:
  1. Receives consolidated data from the `SQS_ConsolidateToExport` queue.
  2. Updates or inserts records in `finalData.csv`.
  3. Saves the file back to the `processed-data` folder in S3.

---

## 5. Justification of AWS Services

### AWS Lambda
- **Scalability**: Automatically adjusts to workload demand.
- **Cost Efficiency**: Pay-per-execution model.
- **Serverless**: Eliminates infrastructure management.

### Amazon EC2
- **Flexibility**: Full control over resource allocation.
- **Custom Execution Environment**: Supports dependencies and configurations.

### Amazon S3
- **Durability**: 99.999999999% object durability.
- **Scalability**: Seamless handling of increasing data.
- **Cost**: Low storage costs.

### Amazon SQS
- **Reliability**: Supports delayed or deferred processing.
- **Decoupling**: Facilitates flexible, fault-tolerant communication.

---

## 6. Performance Comparison (Lambda vs Java Application)

### Execution Time
- **Lambda**: Ideal for quick execution, but may experience cold starts.
- **EC2**: Predictable execution time, though setup and scaling can be slower.

### Resource Usage
- **Lambda**: Automatically scales but requires optimization for execution time limits.
- **EC2**: Manual resource allocation with fine-grained control.

### Cost Efficiency
- **Lambda**: Cost-effective for variable workloads.
- **EC2**: Suitable for long-running, compute-intensive tasks, but may incur higher costs for underutilization.

### Scalability
- **Lambda**: Automatically adjusts to workload demand.
- **EC2**: Requires manual or auto-scaling configuration.

---

## 7. Conclusion
This solution leverages AWS Lambda and EC2 to process large-scale IoT data, offering a scalable, fault-tolerant, and cost-effective architecture. By comparing Lambda functions and EC2-based Java applications, the project identifies trade-offs in cost, performance, and scalability for IoT data processing in the cloud.
