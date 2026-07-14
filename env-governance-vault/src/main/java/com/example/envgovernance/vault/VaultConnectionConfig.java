package com.example.envgovernance.vault;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Configuração de conexão com o HashiCorp Vault, lida diretamente de um mapa plano
 * de variáveis de ambiente — sem dependência de framework.
 *
 * <p>Cada campo usa uma cadeia de dois níveis:
 * <ol>
 *   <li>Chave de propriedade Spring (ex.: {@code env.governance.sources.vault.address}),
 *       presente no mapa quando chamado via Spring Boot (adicionada pelo
 *       {@code SpringEnvVarSourceAdapter.flattenEnvironment}).</li>
 *   <li>Variável de ambiente do S.O. como fallback direto (ex.: {@code VAULT_ADDR}).</li>
 * </ol>
 *
 * <p>Para apps não-Spring, apenas o segundo nível é resolvido (variáveis do S.O.).
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
	 * Cria uma instância lendo o mapa de ambiente no estilo dois-níveis:
	 * propriedade Spring → fallback para variável de ambiente do S.O.
	 */
	public static VaultConnectionConfig from(Map<String, String> env) {
		String address = firstNonBlank(
				env.get("env.governance.sources.vault.address"),
				env.get("VAULT_ADDR"));

		String authMethodStr = firstNonBlank(
				env.get("env.governance.sources.vault.auth.method"),
				env.get("VAULT_AUTH_METHOD"));
		AuthMethod authMethod = AuthMethod.parse(authMethodStr);

		String token = firstNonBlank(
				env.get("env.governance.sources.vault.auth.token"),
				env.get("VAULT_TOKEN"));

		String roleId = firstNonBlank(
				env.get("env.governance.sources.vault.auth.role-id"),
				env.get("VAULT_ROLE_ID"));

		String secretId = firstNonBlank(
				env.get("env.governance.sources.vault.auth.secret-id"),
				env.get("VAULT_SECRET_ID"));

		String pathsProp = firstNonBlank(
				env.get("env.governance.sources.vault.paths"),
				env.get("VAULT_PATHS"));
		List<String> paths = pathsProp != null
				? Arrays.stream(pathsProp.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()
				: List.of();

		int kvVersion = parseIntOrDefault(firstNonBlank(
				env.get("env.governance.sources.vault.kv-version"),
				env.get("VAULT_KV_VERSION")), 2);

		String namespace = firstNonBlank(
				env.get("env.governance.sources.vault.namespace"),
				env.get("VAULT_NAMESPACE"),
				"");

		int timeoutSeconds = parseIntOrDefault(firstNonBlank(
				env.get("env.governance.sources.vault.timeout-seconds"),
				env.get("VAULT_TIMEOUT_SECONDS")), 5);

		boolean tlsSkipVerify = Boolean.parseBoolean(firstNonBlank(
				env.get("env.governance.sources.vault.tls.skip-verify"),
				env.get("VAULT_TLS_SKIP_VERIFY"),
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
