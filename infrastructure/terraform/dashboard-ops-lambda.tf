resource "aws_cloudwatch_log_group" "dashboard_ops" {
  name              = "/aws/lambda/${local.name_prefix}-dashboard-ops"
  retention_in_days = 14

  tags = {
    Project     = local.project_name
    Environment = local.environment
    Component   = "dashboard-ops"
  }
}

resource "aws_lambda_function" "dashboard_ops" {
  function_name = "${local.name_prefix}-dashboard-ops"
  description   = "Read-only operational telemetry for the CloudScale dashboard."

  role    = aws_iam_role.dashboard_ops.arn
  handler = "com.cloudscale.ops.DashboardOpsLambdaHandler"
  runtime = "java21"

  filename = "${path.module}/../../services/dashboard-ops-worker/target/dashboard-ops-worker.jar"

  source_code_hash = filebase64sha256(
    "${path.module}/../../services/dashboard-ops-worker/target/dashboard-ops-worker.jar"
  )

  memory_size = 512
  timeout     = 30

  architectures = ["x86_64"]

  environment {
    variables = {
      CLOUDSCALE_EVENT_BUS                 = aws_cloudwatch_event_bus.cloudscale.name
      CLOUDSCALE_PAYMENT_QUEUE             = aws_sqs_queue.payment.name
      CLOUDSCALE_PAYMENT_DLQ               = aws_sqs_queue.payment_dlq.name
      CLOUDSCALE_INVENTORY_QUEUE           = aws_sqs_queue.inventory.name
      CLOUDSCALE_INVENTORY_DLQ             = aws_sqs_queue.inventory_dlq.name
      CLOUDSCALE_ORDER_STATUS_QUEUE        = aws_sqs_queue.order_status.name
      CLOUDSCALE_ORDER_STATUS_DLQ          = aws_sqs_queue.order_status_dlq.name
      CLOUDSCALE_ORDER_API_FUNCTION        = aws_lambda_function.order_api.function_name
      CLOUDSCALE_PAYMENT_WORKER_FUNCTION   = aws_lambda_function.payment_worker.function_name
      CLOUDSCALE_INVENTORY_WORKER_FUNCTION = aws_lambda_function.inventory_worker.function_name
      CLOUDSCALE_ORDER_STATUS_FUNCTION     = aws_lambda_function.order_status_worker.function_name
      CLOUDSCALE_ORDER_CREATED_RULE        = aws_cloudwatch_event_rule.order_created.name
      CLOUDSCALE_ORDER_STATUS_RULE         = aws_cloudwatch_event_rule.order_status_events.name
    }
  }

  depends_on = [
    aws_cloudwatch_log_group.dashboard_ops,
    aws_iam_role_policy.dashboard_ops
  ]

  tags = {
    Project     = local.project_name
    Environment = local.environment
    Component   = "dashboard-ops"
  }
}
