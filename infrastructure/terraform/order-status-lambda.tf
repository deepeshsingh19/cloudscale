resource "aws_cloudwatch_log_group" "order_status_worker" {
  name              = "/aws/lambda/${local.name_prefix}-order-status-worker"
  retention_in_days = 14

  tags = {
    Project     = local.project_name
    Environment = local.environment
    Component   = "order-status-worker"
  }
}

resource "aws_iam_role" "order_status_worker" {
  name = "${local.name_prefix}-order-status-worker-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"

    Statement = [
      {
        Effect = "Allow"

        Principal = {
          Service = "lambda.amazonaws.com"
        }

        Action = "sts:AssumeRole"
      }
    ]
  })

  tags = {
    Project     = local.project_name
    Environment = local.environment
    Component   = "order-status-worker"
  }
}

resource "aws_iam_role_policy" "order_status_worker" {
  name = "${local.name_prefix}-order-status-worker-policy"
  role = aws_iam_role.order_status_worker.id

  policy = jsonencode({
    Version = "2012-10-17"

    Statement = [
      {
        Sid    = "CloudWatchLogs"
        Effect = "Allow"

        Action = [
          "logs:CreateLogGroup",
          "logs:CreateLogStream",
          "logs:PutLogEvents"
        ]

        Resource = "${aws_cloudwatch_log_group.order_status_worker.arn}:*"
      },
      {
        Sid    = "DynamoDB"
        Effect = "Allow"

        Action = [
          "dynamodb:GetItem",
          "dynamodb:UpdateItem"
        ]

        Resource = aws_dynamodb_table.orders.arn
      },
      {
        Sid    = "SQS"
        Effect = "Allow"

        Action = [
          "sqs:ReceiveMessage",
          "sqs:DeleteMessage",
          "sqs:GetQueueAttributes",
          "sqs:ChangeMessageVisibility"
        ]

        Resource = aws_sqs_queue.order_status.arn
      }
    ]
  })
}

resource "aws_lambda_function" "order_status_worker" {
  function_name = "${local.name_prefix}-order-status-worker"
  description   = "Aggregates payment and inventory results into final order status."

  role    = aws_iam_role.order_status_worker.arn
  handler = "com.cloudscale.status.OrderStatusLambdaHandler"
  runtime = "java21"

  filename = "${path.module}/../../services/order-status-worker/target/order-status-worker.jar"

  source_code_hash = filebase64sha256(
    "${path.module}/../../services/order-status-worker/target/order-status-worker.jar"
  )

  memory_size = 512
  timeout     = 30

  architectures = ["x86_64"]

  environment {
    variables = {
      CLOUDSCALE_TABLE_NAME = aws_dynamodb_table.orders.name
    }
  }

  depends_on = [
    aws_cloudwatch_log_group.order_status_worker,
    aws_iam_role_policy.order_status_worker
  ]

  tags = {
    Project     = local.project_name
    Environment = local.environment
    Component   = "order-status-worker"
  }
}

resource "aws_lambda_event_source_mapping" "order_status_worker_sqs" {
  event_source_arn = aws_sqs_queue.order_status.arn
  function_name    = aws_lambda_function.order_status_worker.arn

  enabled    = true
  batch_size = 1
}
