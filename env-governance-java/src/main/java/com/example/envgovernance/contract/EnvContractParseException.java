package com.example.envgovernance.contract;

/**
 * Lançada quando um arquivo de contrato declarativo não pode ser interpretado.
 * <p>
 * Sempre carrega o nome do recurso ({@link #getResource()}) para facilitar o diagnóstico.
 *
 * @author Sartre Brasil
 * @since 2.2
 * @see EnvContractParser
 */
public class EnvContractParseException extends RuntimeException {

	private final String resource;

	public EnvContractParseException(String resource, String message) {
		super("[env-governance] Falha ao interpretar contrato '" + resource + "': " + message);
		this.resource = resource;
	}

	public EnvContractParseException(String resource, String message, Throwable cause) {
		super("[env-governance] Falha ao interpretar contrato '" + resource + "': " + message, cause);
		this.resource = resource;
	}

	/** Nome do recurso classpath que gerou a falha (ex.: {@code "env-governance.properties"}). */
	public String getResource() {
		return resource;
	}
}
