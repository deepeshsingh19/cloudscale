output "orders_table_name" {
  description = "DynamoDB orders table name."
  value       = aws_dynamodb_table.orders.name
}

output "orders_table_arn" {
  description = "DynamoDB orders table ARN."
  value       = aws_dynamodb_table.orders.arn
}

output "event_bus_name" {
  description = "CloudScale EventBridge event bus name."
  value       = aws_cloudwatch_event_bus.cloudscale.name
}

output "event_bus_arn" {
  description = "CloudScale EventBridge event bus ARN."
  value       = aws_cloudwatch_event_bus.cloudscale.arn
}

output "payment_queue_url" {
  description = "Payment SQS queue URL."
  value       = aws_sqs_queue.payment.url
}

output "payment_queue_arn" {
  description = "Payment SQS queue ARN."
  value       = aws_sqs_queue.payment.arn
}

output "inventory_queue_url" {
  description = "Inventory SQS queue URL."
  value       = aws_sqs_queue.inventory.url
}

output "inventory_queue_arn" {
  description = "Inventory SQS queue ARN."
  value       = aws_sqs_queue.inventory.arn
}

output "payment_worker_function_name" {
  description = "Payment Worker Lambda function name."
  value       = aws_lambda_function.payment_worker.function_name
}

output "payment_worker_function_arn" {
  description = "Payment Worker Lambda function ARN."
  value       = aws_lambda_function.payment_worker.arn
}

output "payment_worker_role_arn" {
  description = "Payment Worker Lambda IAM role ARN."
  value       = aws_iam_role.payment_worker.arn
}

output "inventory_worker_function_name" {
  description = "Inventory Worker Lambda function name."
  value       = aws_lambda_function.inventory_worker.function_name
}

output "inventory_worker_function_arn" {
  description = "Inventory Worker Lambda function ARN."
  value       = aws_lambda_function.inventory_worker.arn
}

output "inventory_worker_role_arn" {
  description = "Inventory Worker Lambda IAM role ARN."
  value       = aws_iam_role.inventory_worker.arn
}
