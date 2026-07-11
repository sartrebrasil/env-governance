package com.example.envgovernance.vault.http;

import com.example.envgovernance.vault.VaultConnectionConfig;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.json.JsonParserFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;

/**
 * Cliente HTTP para o HashiCorp Vault, usando exclusivamente {@link java.net.http.HttpClient}
 * (Java 21) e o {@link org.springframework.boot.json.JsonParser} embutido do Spring Boot.
 * Nenhuma dependência externa é necessária além de {@code spring-boot}.
 *
 * <p>Suporta:
 * <ul>
 *   <li>Autenticação via <b>token</b> e <b>AppRole</b></li>
 *   <li>KV engine versão 1 ({@code response.data}) e versão 2 ({@code response.data.data})</li>
 *   <li>Vault Enterprise namespaces via header {@code X-Vault-Namespace}</li>
 *   <li>Timeout configurável e TLS skip-verify (desenvolvimento apenas)</li>
 * </ul>
 *
 * @author Sartre Brasil
 * @since 1.1
 */
public final class VaultClient {

	private static final Log log = LogFactory.getLog(VaultClient.class);

	private final VaultConnectionConfig config;
	private final HttpClient httpClient;
	private final org.springframework.boot.json.JsonParser jsonParser;

	public VaultClient(VaultConnectionConfig config) {
		this.config = config;
		this.httpClient = buildHttpClient(config);
		this.jsonParser = JsonParserFactory.getJsonParser();
	}

	/**
	 * Lê segredos do caminho especificado.
	 * Para KV v2 insere automaticamente {@code /data/} após o mount point, se necessário.
	 *
	 * @param path caminho no Vault (ex.: {@code secret/myapp})
	 * @return mapa plano {@code chave → valor} dos segredos encontrados
	 */
	public Map<String, Object> readSecrets(String path) {
		String token = authenticate();
		String apiPath = buildApiPath(path);
		String url = config.address().stripTrailing() + "/v1/" + apiPath;

		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.header("X-Vault-Token", token)
				.GET();

		if (config.namespace() != null && !config.namespace().isBlank()) {
			builder.header("X-Vault-Namespace", config.namespace());
		}

		HttpResponse<String> response = send(builder.build(), path);
		if (response.statusCode() != 200) {
			throw new VaultClientException(
					"Vault retornou HTTP " + response.statusCode() + " para o caminho '" + path + "'");
		}

		return extractSecrets(response.body());
	}

	// -------------------------------------------------------------------------
	// autenticação
	// -------------------------------------------------------------------------

	private String authenticate() {
		return switch (config.authMethod()) {
			case TOKEN   -> requireToken();
			case APPROLE -> loginWithAppRole();
		};
	}

	private String requireToken() {
		if (config.token() == null || config.token().isBlank()) {
			throw new VaultClientException(
					"Autenticação TOKEN configurada mas VAULT_TOKEN não está definido");
		}
		return config.token();
	}

	private String loginWithAppRole() {
		if (config.roleId() == null || config.roleId().isBlank()) {
			throw new VaultClientException("AppRole: VAULT_ROLE_ID não está definido");
		}
		if (config.secretId() == null || config.secretId().isBlank()) {
			throw new VaultClientException("AppRole: VAULT_SECRET_ID não está definido");
		}

		String url = config.address().stripTrailing() + "/v1/auth/approle/login";
		String body = "{\"role_id\":\"" + config.roleId() + "\",\"secret_id\":\"" + config.secretId() + "\"}";

		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body));

		if (config.namespace() != null && !config.namespace().isBlank()) {
			builder.header("X-Vault-Namespace", config.namespace());
		}

		HttpResponse<String> response = send(builder.build(), "auth/approle/login");
		if (response.statusCode() != 200) {
			throw new VaultClientException(
					"AppRole login falhou com HTTP " + response.statusCode());
		}

		Map<String, Object> parsed = jsonParser.parseMap(response.body());
		Object auth = parsed.get("auth");
		if (!(auth instanceof Map<?, ?> authMap)) {
			throw new VaultClientException("Resposta AppRole inválida: campo 'auth' ausente ou inválido");
		}
		Object clientToken = authMap.get("client_token");
		if (!(clientToken instanceof String tokenStr) || tokenStr.isBlank()) {
			throw new VaultClientException("Resposta AppRole inválida: 'auth.client_token' ausente");
		}
		return tokenStr;
	}

	// -------------------------------------------------------------------------
	// parsing de resposta
	// -------------------------------------------------------------------------

	@SuppressWarnings("unchecked")
	private Map<String, Object> extractSecrets(String body) {
		Map<String, Object> response = jsonParser.parseMap(body);
		Object data = response.get("data");
		if (!(data instanceof Map<?, ?> dataMap)) {
			return Map.of();
		}

		if (config.kvVersion() == 2) {
			// KV v2: response.data.data
			Object innerData = dataMap.get("data");
			if (innerData instanceof Map<?, ?> innerMap) {
				return (Map<String, Object>) innerMap;
			}
			return Map.of();
		}

		// KV v1: response.data
		return (Map<String, Object>) dataMap;
	}

	// -------------------------------------------------------------------------
	// helpers
	// -------------------------------------------------------------------------

	/**
	 * Para KV v2, insere automaticamente {@code /data/} após o mount point caso o usuário
	 * tenha fornecido o caminho no formato UI ({@code secret/myapp} → {@code secret/data/myapp}).
	 */
	private String buildApiPath(String userPath) {
		if (config.kvVersion() != 2) {
			return userPath;
		}
		int slash = userPath.indexOf('/');
		if (slash < 0) {
			return userPath + "/data";
		}
		String mount = userPath.substring(0, slash);
		String rest = userPath.substring(slash + 1);
		if (rest.startsWith("data/") || rest.equals("data")) {
			return userPath; // usuário já incluiu /data/
		}
		return mount + "/data/" + rest;
	}

	private HttpResponse<String> send(HttpRequest request, String context) {
		try {
			return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		} catch (IOException e) {
			throw new VaultClientException("Erro de I/O ao acessar Vault (" + context + "): " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new VaultClientException("Interrompido ao acessar Vault (" + context + ")", e);
		}
	}

	private static HttpClient buildHttpClient(VaultConnectionConfig config) {
		HttpClient.Builder builder = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(config.timeoutSeconds()))
				.followRedirects(HttpClient.Redirect.NEVER);

		if (config.tlsSkipVerify()) {
			log.warn("[ENV GOVERNANCE] Vault TLS verification desabilitada (tls.skip-verify=true) — NÃO usar em produção!");
			builder.sslContext(createTrustAllSslContext());
		}

		return builder.build();
	}

	@SuppressWarnings("java:S4830")
	private static SSLContext createTrustAllSslContext() {
		try {
			SSLContext sslContext = SSLContext.getInstance("TLS");
			TrustManager[] trustAll = { new X509TrustManager() {
				public void checkClientTrusted(X509Certificate[] c, String a) {}
				public void checkServerTrusted(X509Certificate[] c, String a) {}
				public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
			}};
			sslContext.init(null, trustAll, new SecureRandom());
			return sslContext;
		} catch (NoSuchAlgorithmException | KeyManagementException e) {
			throw new VaultClientException("Falha ao criar SSLContext trust-all", e);
		}
	}
}
