package com.example.envgovernance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Detecta variáveis de ambiente obrigatórias ausentes e aborta o startup com uma
 * mensagem clara antes que o Spring Boot tente criar qualquer bean.
 *
 * <h3>Por que isso importa</h3>
 * <p>
 * O comportamento padrão do Spring Boot é falhar durante o {@code refreshContext}
 * (criação de beans), lançando uma exceção genérica como:
 * <pre>
 *   java.lang.IllegalArgumentException: Could not resolve placeholder 'DB_PASSWORD'
 *       in value "${DB_PASSWORD}"
 * </pre>
 * Esse erro ocorre <em>um por vez</em> — o desenvolvedor corrige uma var, reinicia,
 * descobre a próxima. Com muitas variáveis ausentes, esse ciclo é custoso.
 *
 * <h3>O que este processor faz diferente</h3>
 * <ul>
 *   <li>Roda em {@code HIGHEST_PRECEDENCE + 20}, logo após o
 *       {@link DeclaredVarsScanner} (+15) e <em>antes</em> de qualquer criação de bean.</li>
 *   <li>Lista <em>todas</em> as variáveis ausentes de uma vez, com a chave de propriedade
 *       e o arquivo de origem de cada uma.</li>
 *   <li>Lança {@link MissingRequiredEnvironmentVariablesException}, um tipo específico
 *       que scripts de deploy podem capturar para distinguir falhas de configuração
 *       de outras falhas de startup.</li>
 * </ul>
 *
 * <h3>O que é verificado</h3>
 * <p>
 * Apenas vars registradas como {@code explicit=true, hasDefault=false} — ou seja,
 * placeholders do tipo {@code ${VAR}} sem fallback. Vars derivadas de chave
 * ({@code [key]}) e placeholders com default ({@code ${VAR:fallback}}) são ignorados.
 *
 * <h3>Desabilitando</h3>
 * <pre>
 * env:
 *   governance:
 *     fail-on-missing: false   # desabilita a checagem (útil em testes de integração)
 *     enabled: false           # desabilita toda a lib
 * </pre>
 *
 * @author Sartre Brasil
 * @since 1.0
 * @see DeclaredVarsScanner
 * @see MissingRequiredEnvironmentVariablesException
 */
public class RequiredEnvCheckEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		if (!isActive(environment)) {
			return;
		}

		List<DeclaredVarsRegistry.DeclaredVar> missing = DeclaredVarsRegistry.getAll().values().stream()
				.filter(v -> v.explicit() && !v.hasDefault())
				.filter(v -> !environment.containsProperty(v.envVarName()))
				.sorted(Comparator.comparing(DeclaredVarsRegistry.DeclaredVar::envVarName))
				.toList();

		if (missing.isEmpty()) {
			return;
		}

		String detail = missing.stream()
				.map(v -> String.format("  %-45s → %s  [%s]",
						v.envVarName(), v.propertyKey(), v.sourceFile()))
				.collect(Collectors.joining("\n"));

		String message = String.format(
				"%n===== ENV GOVERNANCE: VARIÁVEIS OBRIGATÓRIAS AUSENTES (%d) =====%n%s%n%s%n"
				+ "Defina as variáveis acima no ambiente antes de iniciar a aplicação.",
				missing.size(),
				detail,
				"=".repeat(57));

		throw new MissingRequiredEnvironmentVariablesException(message, missing);
	}

	private boolean isActive(ConfigurableEnvironment environment) {
		boolean enabled      = environment.getProperty("env.governance.enabled",        Boolean.class, true);
		boolean failOnMissing = environment.getProperty("env.governance.fail-on-missing", Boolean.class, true);
		return enabled && failOnMissing;
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 20;
	}
}
