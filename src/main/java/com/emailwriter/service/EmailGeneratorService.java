package com.emailwriter.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.emailwriter.entity.EmailRequest;
import com.emailwriter.exception.ApiException;

import reactor.util.retry.Retry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class EmailGeneratorService
{
	private static final Logger log = LoggerFactory.getLogger(EmailGeneratorService.class);

	// Longest email text we send to Gemini (keeps requests small and fast).
	private static final int MAX_EMAIL_CHARS = 8000;

	// Each model gets this long (including one retry) before we give up on it.
	private static final Duration PER_MODEL_TIMEOUT = Duration.ofSeconds(25);

	private static final String DEFAULT_TONE = "professional";
	private static final Set<String> ALLOWED_TONES =
			Set.of("professional", "friendly", "casual", "formal", "concise", "enthusiastic");

	private final WebClient webClient;
	private final ObjectMapper mapper = new ObjectMapper();

	@Value("${gemini.api.url}")
	private String geminiApiUrl;

	@Value("${gemini.api.key}")
	private String geminiApiKey;

	// Optional backup model, used automatically when the main model is busy.
	@Value("${gemini.api.fallback-url:}")
	private String geminiFallbackUrl;

	public EmailGeneratorService(WebClient.Builder webClientBuilder)
	{
		this.webClient=webClientBuilder.build();
	}

	public String generateEmailReply(EmailRequest emailRequest)
	{
		if (emailRequest == null || emailRequest.getEmailContent() == null || emailRequest.getEmailContent().isBlank())
		{
			throw new ApiException(HttpStatus.BAD_REQUEST, "The original email text is empty.");
		}

		//We have to build the prompt that is sent to the back-end

		String prompt=buildPrompt(emailRequest);

		//Craft the request because the request should be in a certain format

		Map<String, Object> requestBody=Map.of("contents", List.of(Map.of("parts", List.of(Map.of("text",prompt)))));

		//Do request and get response

		String response;

		try
		{
			response = callGemini(geminiApiUrl, requestBody);
		}
		catch (Exception primaryError)
		{
			ApiException failure = translateError(primaryError);

			if (!canTryFallback(failure))
			{
				throw failure;
			}

			log.warn("Main model failed (HTTP {}). Trying the fallback model.", failure.getStatus().value());

			try
			{
				response = callGemini(geminiFallbackUrl, requestBody);
			}
			catch (Exception fallbackError)
			{
				throw translateError(fallbackError);
			}
		}
		
		//Extract response and return

		return extractResponseContent(response);
	}

	private String callGemini(String url, Map<String, Object> requestBody)
	{
		return webClient.post()
		        .uri(url)
		        .header("Content-Type", "application/json")
		        .header("x-goog-api-key", geminiApiKey)
		        .bodyValue(requestBody)
		        .retrieve()
		        .bodyToMono(String.class)
		        // Retry once, and only when Gemini says it is busy (503).
		        // Never retry a 429: every retry would count against the usage limit again.
		        .retryWhen(Retry.backoff(1, Duration.ofSeconds(1))
		                .filter(ex -> ex instanceof WebClientResponseException w && w.getStatusCode().value() == 503)
		                .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
		        .timeout(PER_MODEL_TIMEOUT)
		        .block();
	}

	// The backup model is used only if one is configured and the main model was busy, slow or rate-limited.
	private boolean canTryFallback(ApiException failure)
	{
		if (geminiFallbackUrl == null || geminiFallbackUrl.isBlank())
		{
			return false;
		}

		int code = failure.getStatus().value();
		return code == 503 || code == 504 || code == 429;
	}

	// Converts any failure into a short, friendly message with the right HTTP status.
	private ApiException translateError(Throwable error)
	{
		Throwable current = error;

		for (int depth = 0; current != null && depth < 10; depth++)
		{
			if (current instanceof ApiException api)
			{
				return api;
			}

			if (current instanceof WebClientResponseException responseError)
			{
				int code = responseError.getStatusCode().value();
				log.warn("Gemini returned HTTP {}", code);
				return fromGeminiStatus(code);
			}

			if (current instanceof TimeoutException)
			{
				log.warn("Gemini did not answer in time");
				return new ApiException(HttpStatus.GATEWAY_TIMEOUT,
						"Gemini took too long to answer. Please try again.");
			}

			if (current instanceof WebClientRequestException)
			{
				log.warn("Could not connect to Gemini: {}", current.getMessage());
				return new ApiException(HttpStatus.BAD_GATEWAY,
						"Could not connect to the Gemini service. Please try again.");
			}

			current = current.getCause();
		}

		log.error("Unexpected error while calling Gemini", error);
		return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
				"Something went wrong while generating the reply. Please try again.");
	}

	private ApiException fromGeminiStatus(int code)
	{
		return switch (code)
		{
			case 429 -> new ApiException(HttpStatus.TOO_MANY_REQUESTS,
					"Gemini's usage limit has been reached for now. Please wait a minute and try again. "
					+ "If it keeps happening, today's free quota may be used up.");
			case 503 -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
					"Gemini is busy right now. Please try again in a few seconds.");
			case 400 -> new ApiException(HttpStatus.BAD_GATEWAY,
					"Gemini rejected the request (HTTP 400). Check the model name in GEMINI_URL and the key in GEMINI_KEY.");
			case 401, 403 -> new ApiException(HttpStatus.BAD_GATEWAY,
					"Gemini rejected the API key. Check GEMINI_KEY.");
			case 404 -> new ApiException(HttpStatus.BAD_GATEWAY,
					"Gemini could not find that model. Check the model name in GEMINI_URL.");
			default -> new ApiException(HttpStatus.BAD_GATEWAY,
					"Gemini returned an error (HTTP " + code + "). Please try again.");
		};
	}

	private String extractResponseContent(String response)
	{
		try
		{
			JsonNode rootNode=mapper.readTree(response);

			String reply = rootNode.path("candidates")
					.path(0)
					.path("content")
					.path("parts")
					.path(0)
					.path("text")
					.asText();

			if (reply == null || reply.isBlank())
			{
				throw new ApiException(HttpStatus.BAD_GATEWAY,
						"Gemini did not return a reply (it may have been blocked). Please try again.");
			}

			return reply.trim();
		}
		catch (ApiException e)
		{
			throw e;
		}
		catch (Exception e)
		{
			log.error("Could not read the response from Gemini", e);
			throw new ApiException(HttpStatus.BAD_GATEWAY,
					"Could not read Gemini's response. Please try again.");
		}
	}

	private String buildPrompt(EmailRequest emailRequest)
	{
		String tone = normalizeTone(emailRequest.getTone());
		String email = emailRequest.getEmailContent().trim();

		if (email.length() > MAX_EMAIL_CHARS)
		{
			email = email.substring(0, MAX_EMAIL_CHARS);
		}

		StringBuilder prompt = new StringBuilder();

		prompt.append("Write a reply to the email below. ");
		prompt.append("Use a ").append(tone).append(" tone. ");
		prompt.append("Return only the body of the reply as plain text: ");
		prompt.append("no subject line, no markdown formatting, and no extra explanation.");
		prompt.append("\n\nOriginal email:\n").append(email);

		return prompt.toString();
	}

	// Only known tones are allowed, so nobody can inject their own instructions through "tone".
	private String normalizeTone(String tone)
	{
		if (tone == null)
		{
			return DEFAULT_TONE;
		}

		String cleaned = tone.trim().toLowerCase();
		return ALLOWED_TONES.contains(cleaned) ? cleaned : DEFAULT_TONE;
	}
}
