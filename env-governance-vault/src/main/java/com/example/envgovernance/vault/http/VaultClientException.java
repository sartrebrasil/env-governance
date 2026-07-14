package com.example.envgovernance.vault.http;

/**
 * Exceção lançada pelo {@link VaultClient} para erros de conexão, autenticação ou parsing.
 *
 * @author Sartre Brasil
 * @since 1.1
 */
public class VaultClientException extends RuntimeException {

	public VaultClientException(String message) {
		super(message);
	}

	public VaultClientException(String message, Throwable cause) {
		super(message, cause);
	}
}
