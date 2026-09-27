resource "aws_cloudwatch_log_group" "inventory_worker" {
  name              = "/aws/lambda/${local.name_prefix}-inventory-worker"
  retention_in_days = 14

  tags = {
    Project     = local.project_name
    Environment = local.environment
    Component   = "inventory-worker"
  }
}

resource "aws_lambda_function" "inventory_worker" {
  function_name = "${local.name_prefix}-inventory-worker"
  description   = "Processes inventory events from the CloudScale inventory queue."

  role    = aws_iam_role.inventory_worker.arn
  handler = "com.cloudscale.inventory.InventoryLambdaHandler"
  runtime = "java21"

  filename = "${path.module}/../../services/inventory-worker/target/inventory-worker.jar"

  source_code_hash = filebase64sha256(
    "${path.module}/../../services/inventory-worker/target/inventory-worker.jar"
  )

  memory_size = 512
  timeout     = 30

  architectures = ["x86_64"]

  environment {
    variables = {
      CLOUDSCALE_TABLE_NAME               = aws_dynamodb_table.orders.name
      CLOUDSCALE_EVENT_BUS                = aws_cloudwatch_event_bus.cloudscale.name
      CLOUDSCALE_INVENTORY_FAILURE_PREFIX = "FAIL-INVENTORY"
    }
  }

  depends_on = [
    aws_cloudwatch_log_group.inventory_worker,
    aws_iam_role_policy.inventory_worker
  ]

  tags = {
    Project     = local.project_name
    Environment = local.environment
    Component   = "inventory-worker"
  }
}

resource "aws_lambda_event_source_mapping" "inventory_worker_sqs" {
  event_source_arn = aws_sqs_queue.inventory.arn
  function_name    = aws_lambda_function.inventory_worker.arn

  enabled    = true
  batch_size = 1
}
