resource "aws_cloudwatch_log_group" "order_api" {
  name              = "/aws/lambda/${local.name_prefix}-order-api"
  retention_in_days = 14

  tags = {
    Project     = local.project_name
    Environment = local.environment
    Component   = "order-api"
  }
}

resource "aws_lambda_function" "order_api" {
  function_name = "${local.name_prefix}-order-api"
  description   = "CloudScale Order API backed by DynamoDB and EventBridge."

  role    = aws_iam_role.order_api.arn
  handler = "com.cloudscale.order.lambda.StreamLambdaHandler"
  runtime = "java21"

  filename = "${path.module}/../../services/order-api/target/order-api-lambda.jar"

  source_code_hash = filebase64sha256(
    "${path.module}/../../services/order-api/target/order-api-lambda.jar"
  )

  memory_size = 1024
  timeout     = 30

  architectures = ["x86_64"]

  environment {
    variables = {
      CLOUDSCALE_REPOSITORY_TYPE          = "dynamodb"
      CLOUDSCALE_DYNAMODB_TABLE           = aws_dynamodb_table.orders.name
      CLOUDSCALE_EVENT_PUBLISHING_ENABLED = "true"
      CLOUDSCALE_EVENT_BUS                = aws_cloudwatch_event_bus.cloudscale.name
      CLOUDSCALE_EVENT_SOURCE             = "cloudscale.order-service"
    }
  }

  depends_on = [
    aws_cloudwatch_log_group.order_api,
    aws_iam_role_policy.order_api
  ]

  tags = {
    Project     = local.project_name
    Environment = local.environment
    Component   = "order-api"
  }
}
