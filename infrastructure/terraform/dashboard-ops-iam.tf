resource "aws_iam_role" "dashboard_ops" {
  name = "${local.name_prefix}-dashboard-ops-role"

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
    Component   = "dashboard-ops"
  }
}

resource "aws_iam_role_policy" "dashboard_ops" {
  name = "${local.name_prefix}-dashboard-ops-policy"
  role = aws_iam_role.dashboard_ops.id

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
        Resource = "${aws_cloudwatch_log_group.dashboard_ops.arn}:*"
      },
      {
        Sid    = "CloudWatchMetrics"
        Effect = "Allow"
        Action = [
          "cloudwatch:GetMetricData"
        ]
        Resource = "*"
      },
      {
        Sid    = "QueueRead"
        Effect = "Allow"
        Action = [
          "sqs:GetQueueUrl",
          "sqs:GetQueueAttributes"
        ]
        Resource = [
          aws_sqs_queue.payment.arn,
          aws_sqs_queue.payment_dlq.arn,
          aws_sqs_queue.inventory.arn,
          aws_sqs_queue.inventory_dlq.arn,
          aws_sqs_queue.order_status.arn,
          aws_sqs_queue.order_status_dlq.arn
        ]
      },
      {
        Sid    = "EventBridgeRead"
        Effect = "Allow"
        Action = [
          "events:DescribeRule",
          "events:ListTargetsByRule"
        ]
        Resource = [
          aws_cloudwatch_event_rule.order_created.arn,
          aws_cloudwatch_event_rule.order_status_events.arn
        ]
      }
    ]
  })
}
