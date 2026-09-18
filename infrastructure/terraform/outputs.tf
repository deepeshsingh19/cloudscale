output "orders_table_name" {
  description = "CloudScale DynamoDB orders table name."
  value       = aws_dynamodb_table.orders.name
}

output "orders_table_arn" {
  description = "CloudScale DynamoDB orders table ARN."
  value       = aws_dynamodb_table.orders.arn
}