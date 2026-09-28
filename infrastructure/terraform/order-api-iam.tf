resource "aws_iam_role" "order_api" {
  name = "${local.name_prefix}-order-api-role"

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
    Component   = "order-api"
  }
}

resource "aws_iam_role_policy" "order_api" {
  name = "${local.name_prefix}-order-api-policy"
  role = aws_iam_role.order_api.id

  policy = jsonencode({
    Version = "2012-10-17"

    Statement = [
      {
        Sid    = "CloudWatchLogs"
        Effect = "Allow"

        Action = [
          "logs:CreateLogStream",
          "logs:PutLogEvents"
        ]

        Resource = "${aws_cloudwatch_log_group.order_api.arn}:*"
      },
      {
        Sid    = "DynamoDB"
        Effect = "Allow"

        Action = [
          "dynamodb:GetItem",
          "dynamodb:PutItem",
          "dynamodb:Scan",
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
      }
    ]
  })
}
