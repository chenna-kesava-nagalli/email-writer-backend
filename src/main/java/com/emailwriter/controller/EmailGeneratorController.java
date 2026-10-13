package com.emailwriter.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.emailwriter.entity.EmailRequest;
import com.emailwriter.service.EmailGeneratorService;

import lombok.AllArgsConstructor;

@RestController
@RequestMapping("/api/email")
@AllArgsConstructor
@CrossOrigin(origins = "*")
public class EmailGeneratorController
{
	private static final MediaType TEXT_UTF8 = MediaType.valueOf("text/plain;charset=UTF-8");

	private final EmailGeneratorService emailGeneratorService;

	@PostMapping("/generate")
	public ResponseEntity<String> generateEmail(@RequestBody EmailRequest emailRequest)
	{
		String response=emailGeneratorService.generateEmailReply(emailRequest);
		return ResponseEntity.ok().contentType(TEXT_UTF8).body(response);
	}

	// Open this in a browser to check (or wake up) the server.
	@GetMapping("/health")
	public ResponseEntity<String> health()
	{
		return ResponseEntity.ok().contentType(TEXT_UTF8).body("Email Writer server is running.");
	}
}
