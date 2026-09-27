resource "aws_iam_role" "inventory_worker" {
  name = "${local.name_prefix}-inventory-worker-role"

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
    Component   = "inventory-worker"
  }
}

resource "aws_iam_role_policy" "inventory_worker" {
  name = "${local.name_prefix}-inventory-worker-policy"
  role = aws_iam_role.inventory_worker.id

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

        Resource = "${aws_cloudwatch_log_group.inventory_worker.arn}:*"
      },
      {
        Sid    = "DynamoDB"
        Effect = "Allow"

        Action = [
          "dynamodb:GetItem",
          "dynamodb:PutItem",
          "dynamodb:UpdateItem",
          "dynamodb:TransactWriteItems"
        ]

        Resource = aws_dynamodb_table.orders.arn
      },
      {
        Sid    = "EventBridge"
        Effect = "Allow"

        Action = [
          "events:PutEvents"
        ]

        Resource = aws_cloudwatch_event_bus.cloudscale.arn
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

        Resource = aws_sqs_queue.inventory.arn
      }
    ]
  })
}
