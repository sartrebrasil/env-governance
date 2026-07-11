package com.example.envgovernance;

import java.util.List;
import java.util.Map;

/**
 * Resultado de uma verificação de governança de variáveis de ambiente.
 *
 * @param missingRequired nomes das variáveis obrigatórias ausentes (vazio = tudo OK)
 * @param resolvedVars    todas as variáveis resolvidas no momento da verificação
 * @param attributions    mapa {@code varName → sourceName} indicando de qual fonte cada var veio
 * @author Sartre Brasil
 * @since 2.0
 */
public record GovernanceResult(
		List<String> missingRequired,
		Map<String, String> resolvedVars,
		Map<String, String> attributions) {

	public boolean hasGaps() {
		return !missingRequired.isEmpty();
	}
}
