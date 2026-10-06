resource "aws_apigatewayv2_api" "order_api" {
  name          = "${local.name_prefix}-order-api"
  protocol_type = "HTTP"
  description   = "CloudScale Order API HTTP API."

  cors_configuration {
    allow_origins = [
      "http://localhost:5173",
      "http://127.0.0.1:5173",
      "https://cloudscale.vercel.app"
    ]

    allow_methods = [
      "GET",
      "POST",
      "OPTIONS"
    ]

    allow_headers = [
      "content-type",
      "idempotency-key"
    ]

    max_age = 300
  }

  tags = {
    Project     = local.project_name
    Environment = local.environment
    Component   = "api-gateway"
  }
}

resource "aws_apigatewayv2_integration" "order_api" {
  api_id = aws_apigatewayv2_api.order_api.id

  integration_type   = "AWS_PROXY"
  integration_uri    = aws_lambda_function.order_api.invoke_arn
  integration_method = "POST"

  payload_format_version = "2.0"
  timeout_milliseconds   = 30000
}

resource "aws_apigatewayv2_route" "create_order" {
  api_id    = aws_apigatewayv2_api.order_api.id
  route_key = "POST /orders"
  target    = "integrations/${aws_apigatewayv2_integration.order_api.id}"
}

resource "aws_apigatewayv2_route" "list_orders" {
  api_id    = aws_apigatewayv2_api.order_api.id
  route_key = "GET /orders"
  target    = "integrations/${aws_apigatewayv2_integration.order_api.id}"
}

resource "aws_apigatewayv2_route" "get_order" {
  api_id    = aws_apigatewayv2_api.order_api.id
  route_key = "GET /orders/{orderId}"
  target    = "integrations/${aws_apigatewayv2_integration.order_api.id}"
}

resource "aws_apigatewayv2_stage" "default" {
  api_id = aws_apigatewayv2_api.order_api.id
  name   = "$default"

  auto_deploy = true
}

resource "aws_lambda_permission" "order_api_gateway" {
  statement_id  = "AllowApiGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.order_api.function_name
  principal     = "apigateway.amazonaws.com"

  source_arn = "${aws_apigatewayv2_api.order_api.execution_arn}/*/*"
}
