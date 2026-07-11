package com.example.envgovernance.source;

/**
 * Lançada pelo {@link EnvVarSourceLoaderPostProcessor} quando uma fonte falha ao inicializar
 * e a política configurada para ela é {@code on-failure=fail}.
 *
 * @author Sartre Brasil
 * @since 1.1
 * @see EnvVarSourceLoaderPostProcessor
 */
public class EnvVarSourceLoadException extends RuntimeException {

	public EnvVarSourceLoadException(String message, Throwable cause) {
		super(message, cause);
	}
}
