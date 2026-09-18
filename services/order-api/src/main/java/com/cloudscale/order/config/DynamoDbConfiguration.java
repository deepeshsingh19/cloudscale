package com.cloudscale.order.config;

import com.cloudscale.order.repository.DynamoDbOrderRepository;
import com.cloudscale.order.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Configuration
@ConditionalOnProperty(
        name = "cloudscale.repository.type",
        havingValue = "dynamodb"
)
public class DynamoDbConfiguration {

    @Bean
    public DynamoDbClient dynamoDbClient() {
        return DynamoDbClient.create();
    }

    @Bean
    public OrderRepository dynamoDbOrderRepository(
            DynamoDbClient dynamoDbClient,
            @Value("${cloudscale.dynamodb.table-name}") String tableName
    ) {
        return new DynamoDbOrderRepository(
                dynamoDbClient,
                tableName
        );
    }
}