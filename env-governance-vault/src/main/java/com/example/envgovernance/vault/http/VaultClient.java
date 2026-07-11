package com.example.envgovernance.vault.http;

import com.example.envgovernance.vault.VaultConnectionConfig;

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
import java.util.logging.Logger;

/**
 * Cliente HTTP para o HashiCorp Vault usando exclusivamente APIs do JDK 21
 * ({@link java.net.http.HttpClient} e {@link VaultJsonParser}) — zero dependências externas.
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

	private static final Logger log = Logger.getLogger(VaultClient.class.getName());

	private final VaultConnectionConfig config;
	private final HttpClient httpClient;

	public VaultClient(VaultConnectionConfig config) {
		this.config = config;
		this.httpClient = buildHttpClient(config);
	}

	/**
	 * Lê segredos do caminho especificado.
	 * Para KV v2 insere automaticamente {@code /data/} após o mount point, se necessário.
	 *
	 * @param path caminho no Vault (ex.: {@code secret/myapp})
	 * @return mapa plano {@code chave → valor} dos segredos encontrados
	 */
	public Map<String, String> readSecrets(String path) {
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

		String clientToken = VaultJsonParser.extractString(response.body(), "auth", "client_token");
		if (clientToken == null || clientToken.isBlank()) {
			throw new VaultClientException("Resposta AppRole inválida: 'auth.client_token' ausente");
		}
		return clientToken;
	}

	// -------------------------------------------------------------------------
	// parsing de resposta
	// -------------------------------------------------------------------------

	private Map<String, String> extractSecrets(String body) {
		if (config.kvVersion() == 2) {
			return VaultJsonParser.extractNestedObject(body, "data", "data");
		}
		return VaultJsonParser.extractNestedObject(body, "data");
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
			return userPath;
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
			log.warning("[ENV GOVERNANCE] Vault TLS verification desabilitada (tls.skip-verify=true) — NÃO usar em produção!");
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
