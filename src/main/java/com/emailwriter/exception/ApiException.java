package com.emailwriter.exception;

import org.springframework.http.HttpStatus;

/**
 * An error with a friendly message that is safe to show to the user.
 */
public class ApiException extends RuntimeException
{
	private static final long serialVersionUID = 1L;

	private final HttpStatus status;

	public ApiException(HttpStatus status, String message)
	{
		super(message);
		this.status = status;
	}

	public HttpStatus getStatus()
	{
		return status;
	}
}
