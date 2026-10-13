package com.emailwriter.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns errors into short plain-text messages that the Gmail extension can show.
 */
@RestControllerAdvice
public class GlobalExceptionHandler
{
	private static final MediaType TEXT_UTF8 = MediaType.valueOf("text/plain;charset=UTF-8");

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<String> handleApiException(ApiException e)
	{
		return ResponseEntity.status(e.getStatus()).contentType(TEXT_UTF8).body(e.getMessage());
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<String> handleBadJson(HttpMessageNotReadableException e)
	{
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(TEXT_UTF8)
				.body("The request was not valid. Please send emailContent and tone as JSON.");
	}
}
