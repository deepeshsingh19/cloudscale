resource "aws_sqs_queue" "order_status_dlq" {
  name = "${local.name_prefix}-order-status-dlq"

  sqs_managed_sse_enabled = true

  tags = {
    Project     = local.project_name
    Environment = local.environment
    Component   = "order-status-dlq"
  }
}

resource "aws_sqs_queue" "order_status" {
  name = "${local.name_prefix}-order-status"

  visibility_timeout_seconds = 30
  message_retention_seconds  = 345600

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.order_status_dlq.arn
    maxReceiveCount     = 3
  })

  sqs_managed_sse_enabled = true

  tags = {
    Project     = local.project_name
    Environment = local.environment
    Component   = "order-status"
  }
}
