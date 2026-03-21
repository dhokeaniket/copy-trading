package com.copytrading.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@ConditionalOnProperty(prefix = "app.kafka", name = "enabled", havingValue = "true")
public class KafkaTopicsConfig {
  @Bean
  public NewTopic tradeEventsTopic() {
    return TopicBuilder.name("trade-events").partitions(3).replicas(1).build();
  }
}

