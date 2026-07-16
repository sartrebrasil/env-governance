package com.example.envgovernance;

import com.example.envgovernance.contract.ContractViolation;

import java.util.List;
import java.util.Map;

/**
 * Resultado de uma verificação de governança de variáveis de ambiente.
 *
 * @param missingRequired nomes das variáveis obrigatórias ausentes (vazio = presença OK).
 *                        É uma visão derivada de {@code violations} — inclui os nomes das
 *                        violações {@link ContractViolation.Kind#MISSING} e
 *                        {@link ContractViolation.Kind#CONDITIONAL_MISSING} —, mantida por
 *                        compatibilidade com o comportamento anterior à validação de contrato.
 * @param resolvedVars    todas as variáveis resolvidas no momento da verificação
 * @param attributions    mapa {@code varName → sourceName} indicando de qual fonte cada var veio
 * @param violations      todas as violações de contrato detectadas (presença + valor); vazio = tudo OK
 * @author Sartre Brasil
 * @since 2.0
 */
public record GovernanceResult(
		List<String> missingRequired,
		Map<String, String> resolvedVars,
		Map<String, String> attributions,
		List<ContractViolation> violations) {

	public GovernanceResult {
		violations = violations == null ? List.of() : List.copyOf(violations);
	}

	/**
	 * Construtor de conveniência sem violações de contrato — preserva a compatibilidade de
	 * código-fonte com chamadores anteriores à introdução do contrato declarativo.
	 */
	public GovernanceResult(List<String> missingRequired,
	                        Map<String, String> resolvedVars,
	                        Map<String, String> attributions) {
		this(missingRequired, resolvedVars, attributions, List.of());
	}

	public boolean hasGaps() {
		return !missingRequired.isEmpty() || !violations.isEmpty();
	}
}
