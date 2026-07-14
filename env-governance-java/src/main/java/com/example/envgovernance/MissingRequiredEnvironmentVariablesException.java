package com.example.envgovernance;

import java.util.List;

/**
 * Lançada quando uma ou mais variáveis de ambiente obrigatórias estão ausentes no momento
 * do startup da aplicação.
 * <p>
 * Por ser um tipo específico, scripts de deploy e ferramentas de monitoramento podem
 * capturá-la para distinguir "falha por configuração" de outras falhas de startup.
 *
 * @author Sartre Brasil
 * @since 2.0
 */
public class MissingRequiredEnvironmentVariablesException extends RuntimeException {

	private final List<String> missingVarNames;

	public MissingRequiredEnvironmentVariablesException(String message, List<String> missingVarNames) {
		super(message);
		this.missingVarNames = List.copyOf(missingVarNames);
	}

	/** Nomes das variáveis ausentes, em ordem alfabética. */
	public List<String> getMissingVarNames() {
		return missingVarNames;
	}
}
