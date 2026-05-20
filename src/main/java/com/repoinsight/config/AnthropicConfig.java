package com.repoinsight.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "analysis.engine", havingValue = "CLAUDE")
public class AnthropicConfig {

    @Value("${anthropic.api-key:}")
    private String apiKey;

    @Bean
    public AnthropicClient anthropicClient() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "analysis.engine=CLAUDE requires ANTHROPIC_API_KEY env var to be set.");
        }
        return AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .build();
    }
}
