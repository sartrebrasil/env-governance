package com.example.envgovernance.vault;

import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Arrays;
import java.util.List;

/**
 * Configuração de conexão com o HashiCorp Vault, lida diretamente do
 * {@link ConfigurableEnvironment} na fase EPP — antes de qualquer bean Spring existir.
 *
 * <p>Cada campo usa uma cadeia de dois níveis:
 * <ol>
 *   <li>Chave de propriedade Spring (ex.: {@code env.governance.sources.vault.address}), que
 *       pode ser definida em {@code application.yml} como {@code ${VAULT_ADDR}}.</li>
 *   <li>Variável de ambiente do S.O. como fallback direto (ex.: {@code VAULT_ADDR}).</li>
 * </ol>
 *
 * <p>Isso implementa o modelo de dois níveis do plano: parâmetros de conexão com o Vault
 * viajam via env do S.O.; segredos da aplicação viajam via Vault.
 *
 * @author Sartre Brasil
 * @since 1.1
 */
public record VaultConnectionConfig(
		String address,
		AuthMethod authMethod,
		String token,
		String roleId,
		String secretId,
		List<String> paths,
		int kvVersion,
		String namespace,
		int timeoutSeconds,
		boolean tlsSkipVerify) {

	public enum AuthMethod {
		TOKEN, APPROLE;

		static AuthMethod parse(String value) {
			if (value == null) return TOKEN;
			return switch (value.toLowerCase().trim()) {
				case "approle" -> APPROLE;
				default        -> TOKEN;
			};
		}
	}

	/**
	 * Cria uma instância lendo o ambiente no estilo dois-níveis:
	 * propriedade Spring → fallback para variável de ambiente do S.O.
	 */
	public static VaultConnectionConfig from(ConfigurableEnvironment env) {
		String address = firstNonBlank(
				env.getProperty("env.governance.sources.vault.address"),
				env.getProperty("VAULT_ADDR"));

		String authMethodStr = firstNonBlank(
				env.getProperty("env.governance.sources.vault.auth.method"),
				env.getProperty("VAULT_AUTH_METHOD"));
		AuthMethod authMethod = AuthMethod.parse(authMethodStr);

		String token = firstNonBlank(
				env.getProperty("env.governance.sources.vault.auth.token"),
				env.getProperty("VAULT_TOKEN"));

		String roleId = firstNonBlank(
				env.getProperty("env.governance.sources.vault.auth.role-id"),
				env.getProperty("VAULT_ROLE_ID"));

		String secretId = firstNonBlank(
				env.getProperty("env.governance.sources.vault.auth.secret-id"),
				env.getProperty("VAULT_SECRET_ID"));

		String pathsProp = firstNonBlank(
				env.getProperty("env.governance.sources.vault.paths"),
				env.getProperty("VAULT_PATHS"));
		List<String> paths = pathsProp != null
				? Arrays.stream(pathsProp.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()
				: List.of();

		int kvVersion = env.getProperty("env.governance.sources.vault.kv-version", Integer.class,
				parseIntOrDefault(env.getProperty("VAULT_KV_VERSION"), 2));

		String namespace = firstNonBlank(
				env.getProperty("env.governance.sources.vault.namespace"),
				env.getProperty("VAULT_NAMESPACE"),
				"");

		int timeoutSeconds = env.getProperty("env.governance.sources.vault.timeout-seconds", Integer.class,
				parseIntOrDefault(env.getProperty("VAULT_TIMEOUT_SECONDS"), 5));

		boolean tlsSkipVerify = Boolean.parseBoolean(firstNonBlank(
				env.getProperty("env.governance.sources.vault.tls.skip-verify"),
				env.getProperty("VAULT_TLS_SKIP_VERIFY"),
				"false"));

		return new VaultConnectionConfig(
				address, authMethod, token, roleId, secretId,
				paths, kvVersion, namespace, timeoutSeconds, tlsSkipVerify);
	}

	private static String firstNonBlank(String... values) {
		for (String v : values) {
			if (v != null && !v.isBlank()) return v;
		}
		return null;
	}

	private static int parseIntOrDefault(String value, int defaultValue) {
		if (value == null) return defaultValue;
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}
}
