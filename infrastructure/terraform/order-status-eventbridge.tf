resource "aws_cloudwatch_event_rule" "order_status_events" {
  name           = "${local.name_prefix}-order-status-events"
  description    = "Routes payment and inventory result events to the order status queue."
  event_bus_name = aws_cloudwatch_event_bus.cloudscale.name

  event_pattern = jsonencode({
    source = [
      "cloudscale.payment-service",
      "cloudscale.inventory-service"
    ]

    detail-type = [
      "PaymentCompleted",
      "PaymentFailed",
      "InventoryReserved",
      "InventoryFailed"
    ]
  })

  tags = {
    Project     = local.project_name
    Environment = local.environment
    Component   = "order-status-events"
  }
}

resource "aws_cloudwatch_event_target" "order_status" {
  rule           = aws_cloudwatch_event_rule.order_status_events.name
  event_bus_name = aws_cloudwatch_event_bus.cloudscale.name
  target_id      = "order-status-queue"
  arn            = aws_sqs_queue.order_status.arn
}

data "aws_iam_policy_document" "order_status_queue" {
  statement {
    sid    = "AllowEventBridgeSendMessage"
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["events.amazonaws.com"]
    }

    actions = [
      "sqs:SendMessage"
    ]

    resources = [
      aws_sqs_queue.order_status.arn
    ]

    condition {
      test     = "ArnEquals"
      variable = "aws:SourceArn"
      values = [
        aws_cloudwatch_event_rule.order_status_events.arn
      ]
    }
  }
}

resource "aws_sqs_queue_policy" "order_status" {
  queue_url = aws_sqs_queue.order_status.url
  policy    = data.aws_iam_policy_document.order_status_queue.json
}
