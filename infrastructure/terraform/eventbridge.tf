resource "aws_cloudwatch_event_bus" "cloudscale" {
  name = "${local.name_prefix}-events"

  tags = {
    Project     = local.project_name
    Environment = local.environment
    Component   = "eventbridge"
  }
}

resource "aws_cloudwatch_event_rule" "order_created" {
  name           = "${local.name_prefix}-order-created"
  description    = "Routes CloudScale OrderCreated events to payment and inventory queues."
  event_bus_name = aws_cloudwatch_event_bus.cloudscale.name

  event_pattern = jsonencode({
    source = ["cloudscale.order-service"]

    detail-type = ["OrderCreated"]
  })

  tags = {
    Project     = local.project_name
    Environment = local.environment
    Component   = "order-created-rule"
  }
}

resource "aws_cloudwatch_event_target" "payment" {
  rule           = aws_cloudwatch_event_rule.order_created.name
  event_bus_name = aws_cloudwatch_event_bus.cloudscale.name
  target_id      = "payment-queue"
  arn            = aws_sqs_queue.payment.arn
}

resource "aws_cloudwatch_event_target" "inventory" {
  rule           = aws_cloudwatch_event_rule.order_created.name
  event_bus_name = aws_cloudwatch_event_bus.cloudscale.name
  target_id      = "inventory-queue"
  arn            = aws_sqs_queue.inventory.arn
}

data "aws_iam_policy_document" "payment_queue" {
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
      aws_sqs_queue.payment.arn
    ]

    condition {
      test     = "ArnEquals"
      variable = "aws:SourceArn"
      values = [
        aws_cloudwatch_event_rule.order_created.arn
      ]
    }
  }
}

data "aws_iam_policy_document" "inventory_queue" {
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
      aws_sqs_queue.inventory.arn
    ]

    condition {
      test     = "ArnEquals"
      variable = "aws:SourceArn"
      values = [
        aws_cloudwatch_event_rule.order_created.arn
      ]
    }
  }
}

resource "aws_sqs_queue_policy" "payment" {
  queue_url = aws_sqs_queue.payment.url
  policy    = data.aws_iam_policy_document.payment_queue.json
}

resource "aws_sqs_queue_policy" "inventory" {
  queue_url = aws_sqs_queue.inventory.url
  policy    = data.aws_iam_policy_document.inventory_queue.json
}