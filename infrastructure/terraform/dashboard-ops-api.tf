resource "aws_apigatewayv2_integration" "dashboard_ops" {
  api_id = aws_apigatewayv2_api.order_api.id

  integration_type   = "AWS_PROXY"
  integration_uri    = aws_lambda_function.dashboard_ops.invoke_arn
  integration_method = "POST"

  payload_format_version = "2.0"
  timeout_milliseconds   = 30000
}

resource "aws_apigatewayv2_route" "dashboard_ops" {
  api_id    = aws_apigatewayv2_api.order_api.id
  route_key = "GET /ops/metrics"
  target    = "integrations/${aws_apigatewayv2_integration.dashboard_ops.id}"
}

resource "aws_lambda_permission" "dashboard_ops_gateway" {
  statement_id  = "AllowApiGatewayInvokeDashboardOps"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.dashboard_ops.function_name
  principal     = "apigateway.amazonaws.com"

  source_arn = "${aws_apigatewayv2_api.order_api.execution_arn}/*/*"
}
