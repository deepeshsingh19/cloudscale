# CloudScale — AWS Cloud-Native Order Processing Platform

CloudScale is a serverless, event-driven order processing platform built with AWS.

The project demonstrates reliable asynchronous order processing using API Gateway, AWS Lambda, DynamoDB, EventBridge, SQS, SNS, CloudWatch, IAM, Terraform, and React.

## Architecture

```text
Client
  │
  ▼
API Gateway
  │
  ▼
Order API Lambda
  │
  ▼
DynamoDB
  │
  │ OrderCreated
  ▼
EventBridge
  ├──► Payment SQS ──► Payment Lambda
  │
  └──► Inventory SQS ──► Inventory Lambda