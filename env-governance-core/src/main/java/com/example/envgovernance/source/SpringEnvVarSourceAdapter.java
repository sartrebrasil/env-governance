package com.example.envgovernance.source;

import com.example.envgovernance.spi.EnvVarSource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Adapta a SPI Java-nativa {@link EnvVarSource} para o ciclo de vida Spring,
 * convertendo entre {@code Map<String,String>} (contrato da SPI) e
 * {@link PropertySource} (abstração Spring).
 *
 * @author Sartre Brasil
 * @since 2.0
 */
final class SpringEnvVarSourceAdapter {

	private SpringEnvVarSourceAdapter() {}

	/**
	 * Constrói um mapa plano a partir de todos os {@link PropertySource}s ativos no ambiente.
	 * First-wins preserva a ordem de prioridade do Spring.
	 * <p>
	 * O resultado é passado para {@link EnvVarSource#isAvailable} e {@link EnvVarSource#load},
	 * permitindo que as fontes leiam tanto propriedades Spring ({@code env.governance.sources.vault.address})
	 * quanto variáveis do S.O. ({@code VAULT_ADDR}) sem depender do Spring diretamente.
	 */
	static Map<String, String> flattenEnvironment(ConfigurableEnvironment env) {
		Map<String, String> result = new LinkedHashMap<>();
		for (PropertySource<?> ps : env.getPropertySources()) {
			// configurationProperties é um composto interno que pode lançar exceções ao enumerar
			if ("configurationProperties".equals(ps.getName())) continue;
			if (ps instanceof EnumerablePropertySource<?> eps) {
				for (String name : eps.getPropertyNames()) {
					result.computeIfAbsent(name, k -> {
						Object val = eps.getProperty(k);
						return val instanceof String s ? s : null;
					});
				}
			}
		}
		return result;
	}

	/**
	 * Adapta o mapa de variáveis retornado por {@link EnvVarSource#load} para um
	 * {@link PropertySource} Spring. Retorna {@link Optional#empty()} se o mapa é vazio
	 * (a fonte não injeta novos valores no ambiente Spring).
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	static Optional<PropertySource<?>> adapt(EnvVarSource source, Map<String, String> vars) {
		if (vars.isEmpty()) return Optional.empty();
		return Optional.of(new MapPropertySource(source.name(), (Map) vars));
	}
}
