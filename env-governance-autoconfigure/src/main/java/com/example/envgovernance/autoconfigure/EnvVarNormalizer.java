package com.example.envgovernance.autoconfigure;

/**
 * Utilitário de normalização de nomes de variáveis de ambiente, compartilhado entre
 * {@link EnvVarUsageReporter} e {@link EnvGovernanceEndpoint}.
 *
 * @author Sartre Brasil
 * @since 1.1
 */
final class EnvVarNormalizer {

	private EnvVarNormalizer() {}

	/** Converte para UPPER_CASE com underscores, tornando a comparação case-insensitive. */
	static String normalize(String key) {
		return key.toUpperCase().replace('-', '_').replace('.', '_');
	}
}
