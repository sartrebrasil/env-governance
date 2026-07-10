package com.example.envgovernance;

import java.util.List;

/**
 * Lançada pelo {@link RequiredEnvCheckEnvironmentPostProcessor} quando uma ou mais
 * variáveis de ambiente obrigatórias (placeholder {@code ${VAR}} sem default) estão
 * ausentes do SO/container no momento do startup.
 * <p>
 * Por ser um tipo específico, scripts de deploy e ferramentas de monitoramento podem
 * capturá-la para distinguir "falha por configuração" de outras falhas de startup.
 *
 * @author Sartre Brasil
 * @since 1.0
 * @see RequiredEnvCheckEnvironmentPostProcessor
 */
public class MissingRequiredEnvironmentVariablesException extends RuntimeException {

	private final List<DeclaredVarsRegistry.DeclaredVar> missingVars;

	public MissingRequiredEnvironmentVariablesException(
			String message, List<DeclaredVarsRegistry.DeclaredVar> missingVars) {
		super(message);
		this.missingVars = List.copyOf(missingVars);
	}

	/** Variáveis ausentes, ordenadas por nome. */
	public List<DeclaredVarsRegistry.DeclaredVar> getMissingVars() {
		return missingVars;
	}
}
