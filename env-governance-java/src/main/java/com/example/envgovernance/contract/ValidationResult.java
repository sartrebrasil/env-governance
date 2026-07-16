package com.example.envgovernance.contract;

/**
 * Resultado da aplicação de um {@link ValueValidator} sobre o valor de uma variável.
 *
 * @param valid   {@code true} se o valor satisfaz a restrição
 * @param message mensagem descritiva quando inválido; vazia quando válido
 * @author Sartre Brasil
 * @since 2.2
 */
public record ValidationResult(boolean valid, String message) {

	private static final ValidationResult OK = new ValidationResult(true, "");

	/** Resultado de sucesso, sem mensagem. */
	public static ValidationResult ok() {
		return OK;
	}

	/** Resultado de falha com a mensagem informada. */
	public static ValidationResult invalid(String message) {
		return new ValidationResult(false, message == null ? "" : message);
	}
}
