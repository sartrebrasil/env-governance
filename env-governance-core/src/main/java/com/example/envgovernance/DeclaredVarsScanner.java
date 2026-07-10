package com.example.envgovernance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Escaneia os {@link PropertySource}s já carregados pelo Spring e registra em
 * {@link DeclaredVarsRegistry} as variáveis de ambiente potenciais sob duas perspectivas:
 *
 * <ol>
 *   <li><b>Relaxed binding (chave)</b> — cada chave é convertida para UPPER_CASE com
 *       underscores: {@code bridge.poller.max-messages-per-poll}
 *       → {@code BRIDGE_POLLER_MAX_MESSAGES_PER_POLL}.
 *       {@link DeclaredVarsRegistry.DeclaredVar#hasYamlValue()} indica se a propriedade
 *       tem valor concreto no YAML; {@code false} representa um gap de configuração.</li>
 *   <li><b>Placeholder explícito (valor)</b> — se o valor contiver {@code ${VAR_NAME}} ou
 *       {@code ${VAR_NAME:default}}, o {@code VAR_NAME} é registrado como variável explícita
 *       com seu nome próprio, que pode diferir do derivado pela chave:
 *       {@code bridge.poller.max-messages-per-poll: ${BRIDGE_MAX_MESSAGES_PER_POLL:100}}
 *       registra tanto {@code BRIDGE_POLLER_MAX_MESSAGES_PER_POLL} (chave) quanto
 *       {@code BRIDGE_MAX_MESSAGES_PER_POLL} (placeholder).</li>
 * </ol>
 *
 * <p>Roda em {@code HIGHEST_PRECEDENCE + 15}, após o
 * {@code ConfigDataEnvironmentPostProcessor} (+10) que carrega os arquivos de configuração.
 *
 * @author Sartre Brasil
 * @since 1.0
 * @see DeclaredVarsRegistry
 */
public class DeclaredVarsScanner implements EnvironmentPostProcessor, Ordered {

	private static final Pattern PLACEHOLDER_PATTERN =
			Pattern.compile("\\$\\{\\s*([a-zA-Z0-9._\\-]+)\\s*(:([^}]*))?}");

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		if (!environment.getProperty("env.governance.enabled", Boolean.class, true)) {
			return;
		}

		DeclaredVarsRegistry.reset();

		for (PropertySource<?> source : environment.getPropertySources()) {
			if (isApplicationConfigSource(source) && source instanceof EnumerablePropertySource<?> enumerable) {
				String sourceFile = extractFileName(source.getName());
				for (String key : enumerable.getPropertyNames()) {
					Object raw = enumerable.getProperty(key);
					String rawStr = raw != null ? raw.toString().trim() : "";
					boolean hasYamlValue = !rawStr.isEmpty();

					// 1) var derivada da chave (relaxed binding) — hasYamlValue captura se há valor concreto
					DeclaredVarsRegistry.register(new DeclaredVarsRegistry.DeclaredVar(
							toEnvVarName(key), key, sourceFile, false, true, hasYamlValue));

					// 2) placeholders explícitos encontrados no valor
					if (hasYamlValue) {
						scanPlaceholders(rawStr, key, sourceFile);
					}
				}
			}
		}
	}

	private void scanPlaceholders(String value, String propertyKey, String sourceFile) {
		Matcher matcher = PLACEHOLDER_PATTERN.matcher(value);
		while (matcher.find()) {
			String placeholderName = matcher.group(1).trim();
			boolean hasDefault = matcher.group(2) != null;
			String envVarName = toEnvVarName(placeholderName);
			// placeholders explícitos sempre têm hasYamlValue=true (a expressão ${} é o próprio valor)
			DeclaredVarsRegistry.register(new DeclaredVarsRegistry.DeclaredVar(
					envVarName, propertyKey, sourceFile, true, hasDefault, true));
		}
	}

	/**
	 * Identifica PropertySources originadas dos arquivos {@code application*.yml/properties}
	 * do classpath.
	 * <p>
	 * Spring Boot 3.x: {@code Config resource 'class path resource [application.yml]' via location '...'}
	 * <br>
	 * Spring Boot 2.x legacy: {@code applicationConfig: [classpath:/application.yml]}
	 */
	private boolean isApplicationConfigSource(PropertySource<?> source) {
		String name = source.getName();
		return name.contains("class path resource [application") || name.startsWith("applicationConfig");
	}

	private String extractFileName(String sourceName) {
		int start = sourceName.lastIndexOf('[');
		int end = sourceName.lastIndexOf(']');
		if (start >= 0 && end > start) {
			return sourceName.substring(start + 1, end);
		}
		return sourceName;
	}

	/**
	 * Converte uma chave de propriedade Spring ou nome de placeholder para o nome de variável
	 * de ambiente equivalente: pontos e hífens viram underscores, tudo maiúsculo.
	 * <p>
	 * Exemplos: {@code spring.application.name} → {@code SPRING_APPLICATION_NAME},
	 * {@code server.ssl.key-store} → {@code SERVER_SSL_KEY_STORE}
	 */
	static String toEnvVarName(String key) {
		return key.toUpperCase().replace('.', '_').replace('-', '_');
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 15;
	}
}
