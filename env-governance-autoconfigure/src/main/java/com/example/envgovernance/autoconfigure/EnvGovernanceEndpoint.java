package com.example.envgovernance.autoconfigure;

import com.example.envgovernance.DeclaredVarsRegistry;
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
 *   <li>{@code gaps.required}   — placeholders obrigatórios ausentes no SO (erro crítico)</li>
 *   <li>{@code gaps.fallback}   — placeholders com default ausentes no SO (usando fallback)</li>
 *   <li>{@code gaps.noValue}    — propriedades sem valor no YAML e sem var de ambiente</li>
 *   <li>{@code unused}          — vars do SO sem correspondência em nenhuma propriedade</li>
 *   <li>{@code applicationVars} — todas as variáveis potenciais (diagnóstico completo)</li>
 *   <li>{@code activeOverrides} — vars do SO que estão sobrescrevendo propriedades</li>
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
 */
@Endpoint(id = "env-governance")
public class EnvGovernanceEndpoint {

	@ReadOperation
	public Map<String, Object> report() {
		Map<String, DeclaredVarsRegistry.DeclaredVar> declared = DeclaredVarsRegistry.getAll();
		Set<String> osEnv = System.getenv().keySet();
		Set<String> declaredNormalized = declared.keySet().stream()
				.map(this::normalize)
				.collect(Collectors.toSet());

		// gaps: [REQUIRED]
		List<Map<String, String>> required = declared.values().stream()
				.filter(v -> v.explicit() && !v.hasDefault())
				.filter(v -> osEnv.stream().noneMatch(k -> normalize(k).equals(normalize(v.envVarName()))))
				.sorted((a, b) -> a.envVarName().compareTo(b.envVarName()))
				.map(v -> Map.of("name", v.envVarName(), "propertyKey", v.propertyKey(), "sourceFile", v.sourceFile()))
				.toList();

		// gaps: [FALLBACK]
		List<Map<String, String>> fallback = declared.values().stream()
				.filter(v -> v.explicit() && v.hasDefault())
				.filter(v -> osEnv.stream().noneMatch(k -> normalize(k).equals(normalize(v.envVarName()))))
				.sorted((a, b) -> a.envVarName().compareTo(b.envVarName()))
				.map(v -> Map.of("name", v.envVarName(), "propertyKey", v.propertyKey(), "sourceFile", v.sourceFile()))
				.toList();

		// gaps: [NO VALUE]
		List<Map<String, String>> noValue = declared.values().stream()
				.filter(v -> !v.explicit() && !v.hasYamlValue())
				.filter(v -> osEnv.stream().noneMatch(k -> normalize(k).equals(normalize(v.envVarName()))))
				.sorted((a, b) -> a.envVarName().compareTo(b.envVarName()))
				.map(v -> Map.of("name", v.envVarName(), "propertyKey", v.propertyKey(), "sourceFile", v.sourceFile()))
				.toList();

		// vars do SO sem correspondência
		List<String> unused = osEnv.stream()
				.filter(k -> !declaredNormalized.contains(normalize(k)))
				.sorted()
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

		List<Map<String, String>> activeOverrides = osEnv.stream()
				.filter(k -> declaredNormalized.contains(normalize(k)))
				.sorted()
				.map(k -> {
					DeclaredVarsRegistry.DeclaredVar matched = findByNormalized(declared, k);
					return matched != null
							? Map.of("envVar", k, "propertyKey", matched.propertyKey(), "sourceFile", matched.sourceFile())
							: Map.of("envVar", k);
				})
				.toList();

		return Map.of(
				"gaps",            Map.of("required", required, "fallback", fallback, "noValue", noValue),
				"unused",          unused,
				"applicationVars", applicationVars,
				"activeOverrides", activeOverrides
		);
	}

	private String normalize(String key) {
		return key.toUpperCase().replace('-', '_').replace('.', '_');
	}

	private DeclaredVarsRegistry.DeclaredVar findByNormalized(
			Map<String, DeclaredVarsRegistry.DeclaredVar> declared, String osVar) {
		String norm = normalize(osVar);
		return declared.values().stream()
				.filter(v -> normalize(v.envVarName()).equals(norm))
				.findFirst()
				.orElse(null);
	}
}
