resource "aws_sqs_queue" "payment_dlq" {
  name = "${local.name_prefix}-payment-dlq"

  sqs_managed_sse_enabled = true

  tags = {
    Project     = local.project_name
    Environment = local.environment
    Component   = "payment-dlq"
  }
}

resource "aws_sqs_queue" "inventory_dlq" {
  name = "${local.name_prefix}-inventory-dlq"

  sqs_managed_sse_enabled = true

  tags = {
    Project     = local.project_name
    Environment = local.environment
    Component   = "inventory-dlq"
  }
}

resource "aws_sqs_queue" "payment" {
  name = "${local.name_prefix}-payment"

  visibility_timeout_seconds = 30
  message_retention_seconds  = 345600

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.payment_dlq.arn
    maxReceiveCount     = 3
  })

  sqs_managed_sse_enabled = true

  tags = {
    Project     = local.project_name
    Environment = local.environment
    Component   = "payment"
  }
}

resource "aws_sqs_queue" "inventory" {
  name = "${local.name_prefix}-inventory"

  visibility_timeout_seconds = 30
  message_retention_seconds  = 345600

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.inventory_dlq.arn
    maxReceiveCount     = 3
  })

  sqs_managed_sse_enabled = true

  tags = {
    Project     = local.project_name
    Environment = local.environment
    Component   = "inventory"
  }
}