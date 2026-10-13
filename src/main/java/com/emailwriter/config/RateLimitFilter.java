package com.emailwriter.config;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Simple protection for a public server: each client (IP address) may call
 * /api/email/generate only a limited number of times per minute.
 * This stops one person from using up the shared Gemini quota.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter
{
	private static final long WINDOW_MS = 60_000L;

	@Value("${app.rate-limit.per-minute:10}")
	private int maxPerMinute;

	// client -> { windowStartMillis, requestCount }
	private final Map<String, long[]> windows = new ConcurrentHashMap<>();

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request)
	{
		boolean isGenerateCall = "POST".equalsIgnoreCase(request.getMethod())
				&& request.getRequestURI().startsWith("/api/email/generate");
		return !isGenerateCall;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException
	{
		String client = clientIp(request);
		long now = System.currentTimeMillis();
		boolean allowed;

		synchronized (windows)
		{
			if (windows.size() > 5000)
			{
				windows.entrySet().removeIf(entry -> now - entry.getValue()[0] > WINDOW_MS);
			}

			long[] window = windows.computeIfAbsent(client, key -> new long[] { now, 0 });

			if (now - window[0] > WINDOW_MS)
			{
				window[0] = now;
				window[1] = 0;
			}

			window[1]++;
			allowed = window[1] <= maxPerMinute;
		}

		if (!allowed)
		{
			response.setStatus(429);
			// Needed so the browser lets the extension read this message.
			response.setHeader("Access-Control-Allow-Origin", "*");
			response.setContentType("text/plain;charset=UTF-8");
			response.getWriter().write("Too many requests from your connection. Please wait a minute and try again.");
			return;
		}

		chain.doFilter(request, response);
	}

	private String clientIp(HttpServletRequest request)
	{
		String forwarded = request.getHeader("X-Forwarded-For");

		if (forwarded != null && !forwarded.isBlank())
		{
			return forwarded.split(",")[0].trim();
		}

		return request.getRemoteAddr();
	}
}
