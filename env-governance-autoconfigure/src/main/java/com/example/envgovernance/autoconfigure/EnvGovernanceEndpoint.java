package com.example.envgovernance.autoconfigure;

import com.example.envgovernance.DeclaredVarsRegistry;
import com.example.envgovernance.source.EnvVarSourceRegistry;
import com.example.envgovernance.spi.EnvVarSource;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Endpoint Actuator que expõe o diagnóstico de variáveis de ambiente em
 * {@code GET /actuator/env-governance}.
 *
 * <p>Resposta:
 * <ul>
 *   <li>{@code gaps.required}   — placeholders obrigatórios ausentes em todas as fontes</li>
 *   <li>{@code gaps.fallback}   — placeholders com default ausentes (usando fallback)</li>
 *   <li>{@code gaps.noValue}    — propriedades sem valor no YAML e sem var de ambiente</li>
 *   <li>{@code unused}          — vars sem correspondência em nenhuma propriedade (com fonte de origem)</li>
 *   <li>{@code applicationVars} — todas as variáveis potenciais (diagnóstico completo)</li>
 *   <li>{@code activeOverrides} — vars que estão sobrescrevendo propriedades (com fonte de origem)</li>
 *   <li>{@code sources}         — resumo das fontes ativas (nome, quantidade de vars, prioridade)</li>
 * </ul>
 *
 * <p>Para expor, adicione ao seu {@code application.yml}:
 * <pre>
 * management:
 *   endpoints:
 *     web:
 *       exposure:
 *         include: env-governance
 * </pre>
 *
 * @author Sartre Brasil
 * @since 1.0
 * @see EnvVarSourceRegistry
 */
@Endpoint(id = "env-governance")
public class EnvGovernanceEndpoint {

	@ReadOperation
	public Map<String, Object> report() {
		Map<String, DeclaredVarsRegistry.DeclaredVar> declared = DeclaredVarsRegistry.getAll();
		Map<String, String> attributions = EnvVarSourceRegistry.getVarAttributions();
		Set<String> allEnvVarNames = attributions.keySet();

		Set<String> allEnvNormalized = allEnvVarNames.stream()
				.map(EnvVarNormalizer::normalize)
				.collect(Collectors.toSet());
		Set<String> declaredNormalized = declared.keySet().stream()
				.map(EnvVarNormalizer::normalize)
				.collect(Collectors.toSet());

		// gaps: [REQUIRED]
		List<Map<String, String>> required = declared.values().stream()
				.filter(v -> v.explicit() && !v.hasDefault())
				.filter(v -> !allEnvNormalized.contains(EnvVarNormalizer.normalize(v.envVarName())))
				.sorted((a, b) -> a.envVarName().compareTo(b.envVarName()))
				.map(v -> Map.of("name", v.envVarName(), "propertyKey", v.propertyKey(), "sourceFile", v.sourceFile()))
				.toList();

		// gaps: [FALLBACK]
		List<Map<String, String>> fallback = declared.values().stream()
				.filter(v -> v.explicit() && v.hasDefault())
				.filter(v -> !allEnvNormalized.contains(EnvVarNormalizer.normalize(v.envVarName())))
				.sorted((a, b) -> a.envVarName().compareTo(b.envVarName()))
				.map(v -> Map.of("name", v.envVarName(), "propertyKey", v.propertyKey(), "sourceFile", v.sourceFile()))
				.toList();

		// gaps: [NO VALUE]
		List<Map<String, String>> noValue = declared.values().stream()
				.filter(v -> !v.explicit() && !v.hasYamlValue())
				.filter(v -> !allEnvNormalized.contains(EnvVarNormalizer.normalize(v.envVarName())))
				.sorted((a, b) -> a.envVarName().compareTo(b.envVarName()))
				.map(v -> Map.of("name", v.envVarName(), "propertyKey", v.propertyKey(), "sourceFile", v.sourceFile()))
				.toList();

		// vars sem correspondência — com fonte de origem
		List<Map<String, String>> unused = allEnvVarNames.stream()
				.filter(k -> !declaredNormalized.contains(EnvVarNormalizer.normalize(k)))
				.sorted()
				.map(k -> Map.of("name", k, "source", attributions.getOrDefault(k, "?")))
				.toList();

		// diagnóstico completo
		List<Map<String, Object>> applicationVars = declared.values().stream()
				.sorted((a, b) -> a.envVarName().compareTo(b.envVarName()))
				.map(v -> {
					String origin = v.explicit()
							? (v.hasDefault() ? "placeholder-optional" : "placeholder-required")
							: (v.hasYamlValue() ? "key-derived" : "key-derived/no-value");
					return Map.<String, Object>of(
							"name", v.envVarName(),
							"propertyKey", v.propertyKey(),
							"sourceFile", v.sourceFile(),
							"origin", origin
					);
				})
				.toList();

		// substituições ativas — com fonte de origem
		List<Map<String, Object>> activeOverrides = allEnvVarNames.stream()
				.filter(k -> declaredNormalized.contains(EnvVarNormalizer.normalize(k)))
				.sorted()
				.map(k -> {
					DeclaredVarsRegistry.DeclaredVar matched = findByNormalized(declared, k);
					String source = attributions.getOrDefault(k, "?");
					if (matched != null) {
						return Map.<String, Object>of(
								"envVar", k,
								"propertyKey", matched.propertyKey(),
								"sourceFile", matched.sourceFile(),
								"source", source);
					}
					return Map.<String, Object>of("envVar", k, "source", source);
				})
				.toList();

		// resumo das fontes ativas
		List<Map<String, Object>> sources = EnvVarSourceRegistry.getActiveSources().stream()
				.map(s -> Map.<String, Object>of(
						"name", s.name(),
						"varCount", s.getVarNames().size(),
						"priority", s.getOrder()))
				.toList();

		return Map.of(
				"gaps",            Map.of("required", required, "fallback", fallback, "noValue", noValue),
				"unused",          unused,
				"applicationVars", applicationVars,
				"activeOverrides", activeOverrides,
				"sources",         sources
		);
	}

	private DeclaredVarsRegistry.DeclaredVar findByNormalized(
			Map<String, DeclaredVarsRegistry.DeclaredVar> declared, String envVar) {
		String norm = EnvVarNormalizer.normalize(envVar);
		return declared.values().stream()
				.filter(v -> EnvVarNormalizer.normalize(v.envVarName()).equals(norm))
				.findFirst()
				.orElse(null);
	}
}
