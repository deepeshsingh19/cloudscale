package com.cloudscale.ops;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataResponse;
import software.amazon.awssdk.services.cloudwatch.model.Metric;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataQuery;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataResult;
import software.amazon.awssdk.services.cloudwatch.model.MetricStat;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.DescribeRuleRequest;
import software.amazon.awssdk.services.eventbridge.model.DescribeRuleResponse;
import software.amazon.awssdk.services.eventbridge.model.ListTargetsByRuleRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

public class DashboardOpsLambdaHandler implements RequestStreamHandler {

    private static final String REGION = env("AWS_REGION", "ap-south-1");
    private static final String EVENT_BUS = env("CLOUDSCALE_EVENT_BUS", "cloudscale-dev-events");
    private static final int METRIC_PUBLISHING_LAG_MINUTES = 2;

    private static final List<String> QUEUE_NAMES = List.of(
            env("CLOUDSCALE_PAYMENT_QUEUE", "cloudscale-dev-payment"),
            env("CLOUDSCALE_INVENTORY_QUEUE", "cloudscale-dev-inventory"),
            env("CLOUDSCALE_ORDER_STATUS_QUEUE", "cloudscale-dev-order-status"));

    private static final Map<String, String> DLQ_BY_QUEUE = Map.of(
            QUEUE_NAMES.get(0), env("CLOUDSCALE_PAYMENT_DLQ", "cloudscale-dev-payment-dlq"),
            QUEUE_NAMES.get(1), env("CLOUDSCALE_INVENTORY_DLQ", "cloudscale-dev-inventory-dlq"),
            QUEUE_NAMES.get(2), env("CLOUDSCALE_ORDER_STATUS_DLQ", "cloudscale-dev-order-status-dlq"));

    private static final Map<String, String> LAMBDA_NAMES = new LinkedHashMap<>();
    private static final Map<String, String> RULE_NAMES = new LinkedHashMap<>();

    static {
        LAMBDA_NAMES.put("orderApi", env("CLOUDSCALE_ORDER_API_FUNCTION", "cloudscale-dev-order-api"));
        LAMBDA_NAMES.put("paymentWorker", env("CLOUDSCALE_PAYMENT_WORKER_FUNCTION", "cloudscale-dev-payment-worker"));
        LAMBDA_NAMES.put("inventoryWorker", env("CLOUDSCALE_INVENTORY_WORKER_FUNCTION", "cloudscale-dev-inventory-worker"));
        LAMBDA_NAMES.put("orderStatusWorker", env("CLOUDSCALE_ORDER_STATUS_FUNCTION", "cloudscale-dev-order-status-worker"));

        RULE_NAMES.put("orderCreated", env("CLOUDSCALE_ORDER_CREATED_RULE", "cloudscale-dev-order-created"));
        RULE_NAMES.put("orderStatus", env("CLOUDSCALE_ORDER_STATUS_RULE", "cloudscale-dev-order-status-events"));
    }

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final CloudWatchClient cloudWatch = CloudWatchClient.create();
    private final SqsClient sqs = SqsClient.create();
    private final EventBridgeClient eventBridge = EventBridgeClient.create();

    @Override
    public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
        try {
            JsonNode event = mapper.readTree(input);
            String method = event.path("requestContext").path("http").path("method").asText("");
            String path = event.path("rawPath").asText("");

            if ("GET".equalsIgnoreCase(method) && "/ops/metrics".equals(path)) {
                writeResponse(output, 200, collectSnapshot());
                return;
            }

            writeResponse(output, 404, Map.of("message", "Not found"));
        } catch (Exception ex) {
            writeResponse(output, 500, Map.of(
                    "message", "Unable to collect operational metrics",
                    "error", rootMessage(ex)));
        }
    }

    private Map<String, Object> collectSnapshot() {
        Instant now = Instant.now();
        Instant metricsEnd = now.minus(Duration.ofMinutes(METRIC_PUBLISHING_LAG_MINUTES));
        Instant start = metricsEnd.minus(Duration.ofMinutes(10));
        Map<String, Double> metrics = loadMetrics(start, metricsEnd);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("generatedAt", now);
        response.put("metricsAsOf", metricsEnd);
        response.put("windowMinutes", 10);
        response.put("metricsPublishingLagMinutes", METRIC_PUBLISHING_LAG_MINUTES);
        response.put("region", REGION);
        response.put("queues", collectQueues(start, now, metrics));
        response.put("lambdas", collectLambdas(metrics));
        response.put("eventBridge", collectEventBridge(start, now, metrics));
        return response;
    }

    private Map<String, Double> loadMetrics(Instant start, Instant end) {
        List<MetricDataQuery> queries = new ArrayList<>();
        Map<String, String> queryNames = new LinkedHashMap<>();

        for (String queueName : QUEUE_NAMES) {
            String key = queueKey(queueName);
            addMetricQuery(queries, queryNames, "sqs_" + key + "_oldest", "AWS/SQS",
                    "ApproximateAgeOfOldestMessage", Map.of("QueueName", queueName), "Maximum");
            addMetricQuery(queries, queryNames, "sqs_" + key + "_sent", "AWS/SQS",
                    "NumberOfMessagesSent", Map.of("QueueName", queueName), "Sum");
            addMetricQuery(queries, queryNames, "sqs_" + key + "_received", "AWS/SQS",
                    "NumberOfMessagesReceived", Map.of("QueueName", queueName), "Sum");
            addMetricQuery(queries, queryNames, "sqs_" + key + "_deleted", "AWS/SQS",
                    "NumberOfMessagesDeleted", Map.of("QueueName", queueName), "Sum");
        }

        for (Map.Entry<String, String> entry : LAMBDA_NAMES.entrySet()) {
            String key = entry.getKey();
            String functionName = entry.getValue();
            addMetricQuery(queries, queryNames, "lambda_" + key + "_invocations", "AWS/Lambda",
                    "Invocations", Map.of("FunctionName", functionName), "Sum");
            addMetricQuery(queries, queryNames, "lambda_" + key + "_errors", "AWS/Lambda",
                    "Errors", Map.of("FunctionName", functionName), "Sum");
            addMetricQuery(queries, queryNames, "lambda_" + key + "_duration", "AWS/Lambda",
                    "Duration", Map.of("FunctionName", functionName), "Average");
        }

        for (Map.Entry<String, String> entry : RULE_NAMES.entrySet()) {
            String key = entry.getKey();
            String ruleName = entry.getValue();
            addMetricQuery(queries, queryNames, "event_" + key + "_matched", "AWS/Events",
                    "MatchedEvents", Map.of("EventBusName", EVENT_BUS, "RuleName", ruleName), "Sum");
            addMetricQuery(queries, queryNames, "event_" + key + "_invocations", "AWS/Events",
                    "Invocations", Map.of("EventBusName", EVENT_BUS, "RuleName", ruleName), "Sum");
            addMetricQuery(queries, queryNames, "event_" + key + "_failed", "AWS/Events",
                    "FailedInvocations", Map.of("EventBusName", EVENT_BUS, "RuleName", ruleName), "Sum");
        }

        GetMetricDataRequest request = GetMetricDataRequest.builder()
                .startTime(start)
                .endTime(end)
                .scanBy("TimestampDescending")
                .metricDataQueries(queries)
                .build();

        GetMetricDataResponse response = cloudWatch.getMetricData(request);
        Map<String, Double> values = new LinkedHashMap<>();

        for (MetricDataResult result : response.metricDataResults()) {
            String queryName = queryNames.get(result.id());
            if (queryName == null) {
                continue;
            }
            values.put(queryName, firstValue(result));
        }
        return values;
    }

    private void addMetricQuery(
            List<MetricDataQuery> queries,
            Map<String, String> queryNames,
            String logicalName,
            String namespace,
            String metricName,
            Map<String, String> dimensions,
            String statistic) {

        List<Dimension> dimensionList = new ArrayList<>();
        dimensions.forEach((name, value) -> dimensionList.add(
                Dimension.builder().name(name).value(value).build()));

        MetricStat metricStat = MetricStat.builder()
                .metric(Metric.builder()
                        .namespace(namespace)
                        .metricName(metricName)
                        .dimensions(dimensionList)
                        .build())
                .period(600)
                .stat(statistic)
                .build();

        String id = "m" + Integer.toUnsignedString(logicalName.hashCode());
        queries.add(MetricDataQuery.builder()
                .id(id)
                .metricStat(metricStat)
                .returnData(true)
                .build());
        queryNames.put(id, logicalName);
    }

    private Map<String, Object> collectQueues(Instant start, Instant end, Map<String, Double> metrics) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String queueName : QUEUE_NAMES) {
            result.put(queueKey(queueName), queueSnapshot(queueName, DLQ_BY_QUEUE.get(queueName), metrics));
        }
        return result;
    }

    private Map<String, Object> queueSnapshot(String queueName, String dlqName, Map<String, Double> metrics) {
        GetQueueAttributesResponse queueAttributes = sqs.getQueueAttributes(
                GetQueueAttributesRequest.builder()
                        .queueUrl(queueUrl(queueName))
                        .attributeNames(
                                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)
                        .build());

        GetQueueAttributesResponse dlqAttributes = sqs.getQueueAttributes(
                GetQueueAttributesRequest.builder()
                        .queueUrl(queueUrl(dlqName))
                        .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
                        .build());

        String key = queueKey(queueName);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", queueName);
        result.put("visible", parseLong(queueAttributes.attributes().get(
                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)));
        result.put("inFlight", parseLong(queueAttributes.attributes().get(
                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)));
        result.put("dlqVisible", parseLong(dlqAttributes.attributes().get(
                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)));
        result.put("oldestAgeSeconds", metricValue(metrics, "sqs_" + key + "_oldest"));
        result.put("sentLast10m", metricValue(metrics, "sqs_" + key + "_sent"));
        result.put("receivedLast10m", metricValue(metrics, "sqs_" + key + "_received"));
        result.put("deletedLast10m", metricValue(metrics, "sqs_" + key + "_deleted"));
        return result;
    }

    private Map<String, Object> collectLambdas(Map<String, Double> metrics) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : LAMBDA_NAMES.entrySet()) {
            String key = entry.getKey();
            double invocations = metricValue(metrics, "lambda_" + key + "_invocations");
            double errors = metricValue(metrics, "lambda_" + key + "_errors");
            double duration = metricValue(metrics, "lambda_" + key + "_duration");

            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("functionName", entry.getValue());
            snapshot.put("invocations", invocations);
            snapshot.put("errors", errors);
            snapshot.put("errorRatePercent", invocations == 0 ? 0 : (errors / invocations) * 100.0);
            snapshot.put("avgDurationMs", duration);
            result.put(key, snapshot);
        }
        return result;
    }

    private Map<String, Object> collectEventBridge(Instant start, Instant end, Map<String, Double> metrics) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : RULE_NAMES.entrySet()) {
            String key = entry.getKey();
            String ruleName = entry.getValue();

            DescribeRuleResponse rule = eventBridge.describeRule(
                    DescribeRuleRequest.builder()
                            .name(ruleName)
                            .eventBusName(EVENT_BUS)
                            .build());

            int targetCount = eventBridge.listTargetsByRule(
                    ListTargetsByRuleRequest.builder()
                            .rule(ruleName)
                            .eventBusName(EVENT_BUS)
                            .build())
                    .targets()
                    .size();

            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("ruleName", ruleName);
            snapshot.put("state", Objects.toString(rule.stateAsString(), "UNKNOWN"));
            snapshot.put("targetCount", targetCount);
            snapshot.put("matchedEvents", metricValue(metrics, "event_" + key + "_matched"));
            snapshot.put("invocations", metricValue(metrics, "event_" + key + "_invocations"));
            snapshot.put("failedInvocations", metricValue(metrics, "event_" + key + "_failed"));
            result.put(key, snapshot);
        }
        return result;
    }

    private static double metricValue(Map<String, Double> metrics, String name) {
        return metrics.getOrDefault(name, 0.0);
    }

    private static double firstValue(MetricDataResult result) {
        if (result.values() == null || result.values().isEmpty()) {
            return 0.0;
        }
        return result.values().get(0);
    }

    private String queueUrl(String queueName) {
        return sqs.getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build()).queueUrl();
    }

    private static long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String queueKey(String queueName) {
        if (queueName.contains("payment")) return "payment";
        if (queueName.contains("inventory")) return "inventory";
        return "orderStatus";
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        String message = current.getMessage();
        while (current.getCause() != null) {
            current = current.getCause();
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
        }
        return message == null ? throwable.getClass().getSimpleName() : message;
    }

    private void writeResponse(OutputStream output, int status, Object body) throws IOException {
        ObjectNode response = mapper.createObjectNode();
        response.put("statusCode", status);
        ObjectNode headers = response.putObject("headers");
        headers.put("Content-Type", "application/json");
        headers.put("Cache-Control", "no-store");
        headers.put("Access-Control-Allow-Origin", "*");
        response.put("body", mapper.writeValueAsString(body));
        mapper.writeValue(output, response);
    }
}
