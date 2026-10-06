# CloudScale — AWS Cloud-Native Order Processing Platform

CloudScale is a serverless, event-driven order processing platform built on AWS. It demonstrates asynchronous order processing, reliable messaging, idempotent APIs, failure handling, and operational visibility using managed cloud services.

**Live dashboard:** https://cloudscale.vercel.app/  
**AWS API:** https://97ptssr9kf.execute-api.ap-south-1.amazonaws.com

## Architecture

```text
                         ┌──────────────────────┐
                         │ React / Vercel       │
                         └──────────┬───────────┘
                                    │
                                    ▼
                         ┌──────────────────────┐
                         │ API Gateway HTTP API │
                         └──────────┬───────────┘
                                    │
                                    ▼
                         ┌──────────────────────┐
                         │ Order API Lambda     │
                         └───────┬──────┬───────┘
                                 │      │
                          persist│      │ OrderCreated
                                 ▼      ▼
                         ┌──────────┐ ┌──────────────┐
                         │DynamoDB  │ │  EventBridge │
                         │  Orders  │ └──────┬───────┘
                         └──────────┘        │
                                ┌────────────┴─────────────┐
                                │                          │
                                ▼                          ▼
                       ┌────────────────┐        ┌─────────────────┐
                       │ Payment SQS    │        │ Inventory SQS   │
                       └───────┬────────┘        └────────┬────────┘
                               ▼                          ▼
                       ┌────────────────┐        ┌─────────────────┐
                       │ Payment Lambda │        │ Inventory Lambda│
                       └───────┬────────┘        └────────┬────────┘
                               │                          │
                         result event                result event
                               └────────────┬─────────────┘
                                            ▼
                                   ┌────────────────┐
                                   │  EventBridge   │
                                   │ result events  │
                                   └───────┬────────┘
                                           ▼
                                  ┌──────────────────┐
                                  │ Order Status SQS │
                                  └────────┬─────────┘
                                           ▼
                                  ┌────────────────────┐
                                  │ Order Status Lambda│
                                  └─────────┬──────────┘
                                            ▼
                                         DynamoDB
```

The operational dashboard uses a read-only Lambda to collect CloudWatch, SQS, and EventBridge telemetry through:

```text
GET /ops/metrics
       │
       ▼
Dashboard Ops Lambda
       ├── CloudWatch metrics
       ├── SQS queue / DLQ depth
       └── EventBridge rule state / delivery metrics
```

## AWS components

- **API Gateway HTTP API** — synchronous REST entry point.
- **AWS Lambda** — order API, payment worker, inventory worker, order-status worker, and operations telemetry.
- **DynamoDB** — order and processing state.
- **EventBridge** — decoupled domain-event routing and fan-out.
- **SQS** — asynchronous buffering and worker isolation.
- **SQS DLQs** — failed-message isolation after retry exhaustion.
- **CloudWatch** — Lambda, SQS, and EventBridge telemetry.
- **IAM** — service-specific execution roles and permissions.
- **Terraform** — infrastructure as code.
- **React + Vite** — operations dashboard.

SNS is not required by the current implementation and is intentionally not part of the architecture.

## Order lifecycle

A successful order follows this flow:

```text
CREATED
  │
  ├── Payment → COMPLETED
  │
  └── Inventory → RESERVED
          │
          ▼
       FULFILLED
```

Failures are propagated through the same event-driven flow and result in a `FAILED` order state.

The payment and inventory workers use an outbox-style persistence flow so the processing result can be retried and republished safely if event publication fails. The order-status worker uses conditional state updates with bounded retry handling for concurrent updates.

## Failure simulation

For testing, the workers support deterministic failure prefixes:

- Customer IDs beginning with `FAIL-PAYMENT` simulate a payment failure.
- Customer IDs beginning with `FAIL-INVENTORY` simulate an inventory failure.

Failed SQS messages are retried and eventually moved to their corresponding DLQ after the configured receive limit.

## Repository structure

```text
cloudscale/
├── frontend/
│   ├── src/
│   ├── public/
│   ├── package.json
│   └── .env.example
├── infrastructure/
│   └── terraform/
│       ├── API Gateway
│       ├── EventBridge
│       ├── DynamoDB
│       ├── SQS / DLQs
│       ├── Lambda
│       └── IAM
├── services/
│   ├── order-api/
│   ├── payment-worker/
│   ├── inventory-worker/
│   ├── order-status-worker/
│   └── dashboard-ops-worker/
└── README.md
```

## Local development

### Prerequisites

- Java 21
- Maven
- Node.js / npm
- Terraform >= 1.6
- AWS CLI configured with credentials for the target AWS account

Verify AWS access with:

```bash
aws sts get-caller-identity
```

### 1. Run backend tests

All backend modules contain unit tests.

```bash
for dir in services/*; do
  (cd "$dir" && mvn test)
done
```

### 2. Build Lambda artifacts

Terraform packages the generated JARs from each service's `target/` directory, so build the services before running Terraform.

```bash
for dir in services/*; do
  (cd "$dir" && mvn clean package)
done
```

### 3. Validate and deploy infrastructure

```bash
cd infrastructure/terraform

terraform init
terraform fmt -check
terraform validate
terraform plan
terraform apply
```

The API endpoint is available from:

```bash
terraform output -raw order_api_gateway_endpoint
```

### 4. Run the frontend

```bash
cd frontend
cp .env.example .env
npm install
npm run dev
```

Set `VITE_API_BASE_URL` in `.env` to the API Gateway endpoint.

For a production build:

```bash
npm run build
```

## API

### List orders

```bash
curl -s \
  "https://97ptssr9kf.execute-api.ap-south-1.amazonaws.com/orders" | jq
```

### Create an order

```bash
curl -i -X POST \
  "https://97ptssr9kf.execute-api.ap-south-1.amazonaws.com/orders" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: test-$(date +%s)" \
  -d '{
    "customerId": "CUST-TEST-001",
    "currency": "INR",
    "items": [
      {
        "productId": "PROD-001",
        "quantity": 1,
        "unitPrice": 1499
      }
    ]
  }'
```

The API returns an order in `CREATED` state. Asynchronous workers then update payment, inventory, and final order status.

### Get an order

```bash
curl -s \
  "https://97ptssr9kf.execute-api.ap-south-1.amazonaws.com/orders/<ORDER_ID>" | jq
```

### Operations metrics

```bash
curl -s \
  "https://97ptssr9kf.execute-api.ap-south-1.amazonaws.com/ops/metrics" | jq
```

The operations endpoint reports queue depth, DLQ depth, recent SQS activity, Lambda invocations/errors/duration, and EventBridge rule delivery metrics.

## End-to-end verification

A healthy order should eventually report:

```text
status:          FULFILLED
paymentStatus:   COMPLETED
inventoryStatus: RESERVED
```

A successful operational snapshot should show:

```text
DLQ messages:        0
Lambda errors:       0
EventBridge failures: 0
```

## Frontend deployment

The dashboard is deployed on Vercel.

Set the Vercel environment variable:

```text
VITE_API_BASE_URL=<API Gateway endpoint>
```

Then deploy the `frontend/` directory with Vite.

## Cleanup

Terraform owns the AWS resources created by this project. To remove the deployed infrastructure:

```bash
cd infrastructure/terraform
terraform destroy
```

Review the plan carefully before confirming destruction.

## Design goals

CloudScale focuses on:

- asynchronous, decoupled processing;
- explicit retry and DLQ behavior;
- idempotent order creation;
- DynamoDB-backed state transitions;
- safe event publication using persisted outbox records;
- least-privilege service IAM;
- infrastructure managed with Terraform;
- operational visibility without giving the dashboard write access to application state.
