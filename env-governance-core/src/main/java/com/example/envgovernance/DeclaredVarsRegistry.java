package com.example.envgovernance;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registro estático das variáveis de ambiente potenciais derivadas dos arquivos
 * {@code application*.yml/properties}, populado pelo {@link DeclaredVarsScanner}.
 * <p>
 * Duas origens de registro:
 * <ul>
 *   <li><b>Chave (relaxed binding)</b> — {@code bridge.poller.max-messages-per-poll}
 *       gera {@code BRIDGE_POLLER_MAX_MESSAGES_PER_POLL}.</li>
 *   <li><b>Placeholder explícito</b> — o valor {@code ${BRIDGE_MAX_MESSAGES_PER_POLL:100}}
 *       registra {@code BRIDGE_MAX_MESSAGES_PER_POLL} com nome próprio, que pode diferir
 *       do derivado pela chave.</li>
 * </ul>
 *
 * @author Sartre Brasil
 * @since 1.0
 * @see DeclaredVarsScanner
 */
public final class DeclaredVarsRegistry {

	/**
	 * @param envVarName   nome da variável de ambiente em UPPER_CASE
	 * @param propertyKey  chave da propriedade Spring de origem
	 * @param sourceFile   arquivo de configuração onde foi encontrado
	 * @param explicit     {@code true} quando veio de um placeholder {@code ${VAR}} no valor;
	 *                     {@code false} quando derivado da chave via relaxed binding
	 * @param hasDefault   {@code false} indica placeholder sem fallback (variável obrigatória);
	 *                     sempre {@code true} para vars derivadas de chave
	 * @param hasYamlValue {@code false} indica que a propriedade não tem valor concreto no YAML
	 *                     (null/vazio) — só significativo para vars key-derived; placeholders
	 *                     explícitos sempre têm {@code true} (a expressão {@code ${}} é o valor)
	 */
	public record DeclaredVar(
			String envVarName,
			String propertyKey,
			String sourceFile,
			boolean explicit,
			boolean hasDefault,
			boolean hasYamlValue) {}

	private static final Map<String, DeclaredVar> VARS = new ConcurrentHashMap<>();

	private DeclaredVarsRegistry() {}

	/**
	 * Registra uma variável. Se o mesmo {@code envVarName} já existir, um placeholder
	 * explícito ({@code explicit=true}) tem precedência sobre um derivado de chave.
	 */
	static void register(DeclaredVar var) {
		VARS.merge(var.envVarName(), var, (existing, incoming) -> {
			if (incoming.explicit() && !existing.explicit()) {
				return incoming;
			}
			return existing;
		});
	}

	public static Map<String, DeclaredVar> getAll() {
		return Map.copyOf(VARS);
	}

	/** Limpa o estado — usado em testes para isolar execuções. */
	public static void reset() {
		VARS.clear();
	}
}
