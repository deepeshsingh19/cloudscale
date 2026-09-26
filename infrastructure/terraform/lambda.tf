resource "aws_cloudwatch_log_group" "payment_worker" {
  name              = "/aws/lambda/${local.name_prefix}-payment-worker"
  retention_in_days = 14

  tags = {
    Project     = local.project_name
    Environment = local.environment
    Component   = "payment-worker"
  }
}

resource "aws_lambda_function" "payment_worker" {
  function_name = "${local.name_prefix}-payment-worker"
  description   = "Processes payment events from the CloudScale payment queue."

  role    = aws_iam_role.payment_worker.arn
  handler = "com.cloudscale.payment.PaymentLambdaHandler"
  runtime = "java21"

  filename         = "${path.module}/../../services/payment-worker/target/payment-worker.jar"
  source_code_hash = filebase64sha256("${path.module}/../../services/payment-worker/target/payment-worker.jar")

  memory_size = 512
  timeout     = 30

  architectures = ["x86_64"]

  environment {
    variables = {
      CLOUDSCALE_TABLE_NAME             = aws_dynamodb_table.orders.name
      CLOUDSCALE_EVENT_BUS              = aws_cloudwatch_event_bus.cloudscale.name
      CLOUDSCALE_PAYMENT_FAILURE_PREFIX = "FAIL-PAYMENT"
    }
  }

  depends_on = [
    aws_cloudwatch_log_group.payment_worker,
    aws_iam_role_policy.payment_worker
  ]

  tags = {
    Project     = local.project_name
    Environment = local.environment
    Component   = "payment-worker"
  }
}

resource "aws_lambda_event_source_mapping" "payment_worker_sqs" {
  event_source_arn = aws_sqs_queue.payment.arn
  function_name    = aws_lambda_function.payment_worker.arn

  enabled    = true
  batch_size = 1
}
