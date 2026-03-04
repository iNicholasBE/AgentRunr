package io.agentrunr.config;

import io.agentrunr.setup.CredentialStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mistralai.MistralAiChatModel;
import org.springframework.ai.mistralai.MistralAiChatOptions;
import org.springframework.ai.mistralai.api.MistralAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Manual Mistral AI bean configuration that reads the API key from the CredentialStore
 * (encrypted on disk) rather than relying on Spring AI's auto-configuration
 * which only reads from application.yml / env vars.
 */
@Configuration
public class MistralConfig {

    private static final Logger log = LoggerFactory.getLogger(MistralConfig.class);

    @Bean
    @ConditionalOnMissingBean(name = "mistralAiChatModel")
    public MistralAiChatModel mistralAiChatModel(
            CredentialStore credentialStore,
            @Value("${spring.ai.mistralai.chat.options.model:mistral-medium-latest}") String model) {

        String apiKey = credentialStore.getApiKey("mistral");
        if (apiKey == null || apiKey.isBlank()) {
            log.info("No Mistral AI API key found — Mistral provider will not be available");
            return null;
        }

        log.info("Creating Mistral AI chat model from credential store (model: {})", model);
        MistralAiApi api = new MistralAiApi(
                "https://api.mistral.ai",
                apiKey,
                org.springframework.web.client.RestClient.builder(),
                org.springframework.web.reactive.function.client.WebClient.builder(),
                org.springframework.ai.retry.RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER
        );
        return MistralAiChatModel.builder()
                .mistralAiApi(api)
                .defaultOptions(MistralAiChatOptions.builder().model(model).build())
                .build();
    }
}
