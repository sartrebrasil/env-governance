package com.example.envgovernance.vault.http;

import com.example.envgovernance.vault.VaultConnectionConfig;
import com.example.envgovernance.vault.VaultConnectionConfig.AuthMethod;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes unitários para {@link VaultClient} usando um servidor HTTP embutido do JDK
 * ({@code com.sun.net.httpserver}) — sem dependências externas além do JDK 21.
 *
 * @author Sartre Brasil
 * @since 1.1
 */
class VaultClientTest {

	private HttpServer server;
	private String vaultUrl;

	@BeforeEach
	void setUp() throws IOException {
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.setExecutor(null);
		server.start();
		vaultUrl = "http://127.0.0.1:" + server.getAddress().getPort();
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	// -------------------------------------------------------------------------
	// helpers
	// -------------------------------------------------------------------------

	private void stubGet(String path, int status, String body) {
		server.createContext(path, exchange -> respond(exchange, status, body));
	}

	private void stubPost(String path, int status, String body) {
		server.createContext(path, exchange -> respond(exchange, status, body));
	}

	private void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status, bytes.length);
		try (var out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	/** Config mínima com token auth e KV v2. */
	private VaultConnectionConfig tokenConfig(String... paths) {
		return new VaultConnectionConfig(
				vaultUrl, AuthMethod.TOKEN, "test-token",
				null, null,
				List.of(paths), 2, null, 5, false);
	}

	/** Config com KV v1. */
	private VaultConnectionConfig tokenConfigKvV1(String path) {
		return new VaultConnectionConfig(
				vaultUrl, AuthMethod.TOKEN, "test-token",
				null, null,
				List.of(path), 1, null, 5, false);
	}

	/** Config com AppRole. */
	private VaultConnectionConfig approleConfig(String path) {
		return new VaultConnectionConfig(
				vaultUrl, AuthMethod.APPROLE, null,
				"my-role-id", "my-secret-id",
				List.of(path), 2, null, 5, false);
	}

	// -------------------------------------------------------------------------
	// KV v2 — token auth
	// -------------------------------------------------------------------------

	@Test
	void deveLerSegredosKvV2ComTokenAuth() {
		stubGet("/v1/secret/data/myapp", 200, """
				{"data":{"data":{"DB_PASSWORD":"secret123","DB_URL":"jdbc://localhost"}}}
				""");

		Map<String, String> secrets = new VaultClient(tokenConfig("secret/myapp"))
				.readSecrets("secret/myapp");

		assertEquals("secret123", secrets.get("DB_PASSWORD"));
		assertEquals("jdbc://localhost", secrets.get("DB_URL"));
		assertEquals(2, secrets.size());
	}

	@Test
	void deveInserirDataAutomaticamentePoraCaminhoKvV2() {
		// usuário fornece "secret/myapp", o client deve chamar "/v1/secret/data/myapp"
		stubGet("/v1/secret/data/myapp", 200, """
				{"data":{"data":{"KEY":"value"}}}
				""");

		Map<String, String> secrets = new VaultClient(tokenConfig("secret/myapp"))
				.readSecrets("secret/myapp");

		assertEquals("value", secrets.get("KEY"));
	}

	@Test
	void naoDeveDuplicarDataSeJaPresente() {
		// usuário fornece "secret/data/myapp" — client não deve chamar "/v1/secret/data/data/myapp"
		stubGet("/v1/secret/data/myapp", 200, """
				{"data":{"data":{"KEY":"value"}}}
				""");

		Map<String, String> secrets = new VaultClient(tokenConfig("secret/data/myapp"))
				.readSecrets("secret/data/myapp");

		assertEquals("value", secrets.get("KEY"));
	}

	// -------------------------------------------------------------------------
	// KV v1
	// -------------------------------------------------------------------------

	@Test
	void deveLerSegredosKvV1SemInserirDataNoPath() {
		// KV v1: path sem inserção de /data/, resposta em response.data (não response.data.data)
		stubGet("/v1/secret/myapp", 200, """
				{"data":{"APP_SECRET":"abc","APP_KEY":"xyz"}}
				""");

		Map<String, String> secrets = new VaultClient(tokenConfigKvV1("secret/myapp"))
				.readSecrets("secret/myapp");

		assertEquals("abc", secrets.get("APP_SECRET"));
		assertEquals("xyz", secrets.get("APP_KEY"));
	}

	// -------------------------------------------------------------------------
	// AppRole
	// -------------------------------------------------------------------------

	@Test
	void deveAutenticarComAppRoleELerSegredos() {
		AtomicReference<String> capturedBody = new AtomicReference<>();

		server.createContext("/v1/auth/approle/login", exchange -> {
			capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			respond(exchange, 200, """
					{"auth":{"client_token":"s.generated-token-123"}}
					""");
		});
		server.createContext("/v1/secret/data/myapp", exchange -> {
			// verifica que usou o token obtido via AppRole
			String token = exchange.getRequestHeaders().getFirst("X-Vault-Token");
			assertEquals("s.generated-token-123", token);
			respond(exchange, 200, """
					{"data":{"data":{"SECRET":"value"}}}
					""");
		});

		Map<String, String> secrets = new VaultClient(approleConfig("secret/myapp"))
				.readSecrets("secret/myapp");

		assertEquals("value", secrets.get("SECRET"));
		// verifica que role_id e secret_id foram enviados no login
		assertTrue(capturedBody.get().contains("my-role-id"));
		assertTrue(capturedBody.get().contains("my-secret-id"));
	}

	@Test
	void deveEnviarHeaderAuthorizationCorretoComToken() {
		AtomicReference<String> capturedToken = new AtomicReference<>();
		server.createContext("/v1/secret/data/myapp", exchange -> {
			capturedToken.set(exchange.getRequestHeaders().getFirst("X-Vault-Token"));
			respond(exchange, 200, """
					{"data":{"data":{"KEY":"val"}}}
					""");
		});

		new VaultClient(tokenConfig("secret/myapp")).readSecrets("secret/myapp");

		assertEquals("test-token", capturedToken.get());
	}

	// -------------------------------------------------------------------------
	// namespace
	// -------------------------------------------------------------------------

	@Test
	void deveEnviarHeaderNamespaceQuandoConfigurado() {
		AtomicReference<String> capturedNs = new AtomicReference<>();
		server.createContext("/v1/secret/data/myapp", exchange -> {
			capturedNs.set(exchange.getRequestHeaders().getFirst("X-Vault-Namespace"));
			respond(exchange, 200, """
					{"data":{"data":{}}}
					""");
		});

		VaultConnectionConfig config = new VaultConnectionConfig(
				vaultUrl, AuthMethod.TOKEN, "test-token",
				null, null,
				List.of("secret/myapp"), 2, "my-org/dev", 5, false);

		new VaultClient(config).readSecrets("secret/myapp");

		assertEquals("my-org/dev", capturedNs.get());
	}

	// -------------------------------------------------------------------------
	// erros HTTP
	// -------------------------------------------------------------------------

	@Test
	void deveLancarExcecaoQuandoVaultRetorna403() {
		stubGet("/v1/secret/data/myapp", 403, "{\"errors\":[\"permission denied\"]}");

		VaultClientException ex = assertThrows(VaultClientException.class,
				() -> new VaultClient(tokenConfig("secret/myapp")).readSecrets("secret/myapp"));

		assertTrue(ex.getMessage().contains("403"));
	}

	@Test
	void deveLancarExcecaoQuandoVaultRetorna404() {
		stubGet("/v1/secret/data/myapp", 404, "{\"errors\":[]}");

		VaultClientException ex = assertThrows(VaultClientException.class,
				() -> new VaultClient(tokenConfig("secret/myapp")).readSecrets("secret/myapp"));

		assertTrue(ex.getMessage().contains("404"));
	}

	@Test
	void deveLancarExcecaoQuandoAppRoleLoginRetornaNaoOk() {
		stubPost("/v1/auth/approle/login", 400, "{\"errors\":[\"invalid role id\"]}");

		assertThrows(VaultClientException.class,
				() -> new VaultClient(approleConfig("secret/myapp")).readSecrets("secret/myapp"));
	}

	// -------------------------------------------------------------------------
	// validações de config (sem chamada HTTP)
	// -------------------------------------------------------------------------

	@Test
	void deveLancarExcecaoQuandoTokenNaoConfigurado() {
		VaultConnectionConfig config = new VaultConnectionConfig(
				vaultUrl, AuthMethod.TOKEN, "",
				null, null,
				List.of("secret/myapp"), 2, null, 5, false);

		assertThrows(VaultClientException.class,
				() -> new VaultClient(config).readSecrets("secret/myapp"));
	}

	@Test
	void deveLancarExcecaoQuandoAppRoleRoleIdFaltando() {
		VaultConnectionConfig config = new VaultConnectionConfig(
				vaultUrl, AuthMethod.APPROLE, null,
				"", "my-secret-id",
				List.of("secret/myapp"), 2, null, 5, false);

		assertThrows(VaultClientException.class,
				() -> new VaultClient(config).readSecrets("secret/myapp"));
	}

	@Test
	void deveLancarExcecaoQuandoAppRoleSecretIdFaltando() {
		VaultConnectionConfig config = new VaultConnectionConfig(
				vaultUrl, AuthMethod.APPROLE, null,
				"my-role-id", null,
				List.of("secret/myapp"), 2, null, 5, false);

		assertThrows(VaultClientException.class,
				() -> new VaultClient(config).readSecrets("secret/myapp"));
	}

	@Test
	void deveLancarExcecaoQuandoAppRoleRespostaInvalidaSemAuth() {
		stubPost("/v1/auth/approle/login", 200, "{\"data\":{}}"); // sem campo "auth"

		assertThrows(VaultClientException.class,
				() -> new VaultClient(approleConfig("secret/myapp")).readSecrets("secret/myapp"));
	}

	@Test
	void deveLancarExcecaoQuandoAppRoleClientTokenVazio() {
		stubPost("/v1/auth/approle/login", 200, "{\"auth\":{\"client_token\":\"\"}}");

		assertThrows(VaultClientException.class,
				() -> new VaultClient(approleConfig("secret/myapp")).readSecrets("secret/myapp"));
	}

	// -------------------------------------------------------------------------
	// parsing defensivo
	// -------------------------------------------------------------------------

	@Test
	void deveRetornarMapaVazioQuandoRespostaKvV2SemSegredos() {
		stubGet("/v1/secret/data/myapp", 200, "{\"data\":{\"data\":{}}}");

		Map<String, String> secrets = new VaultClient(tokenConfig("secret/myapp"))
				.readSecrets("secret/myapp");

		assertTrue(secrets.isEmpty());
	}

	@Test
	void deveRetornarMapaVazioQuandoCampoDataAusente() {
		stubGet("/v1/secret/data/myapp", 200, "{\"lease_id\":\"\",\"renewable\":false}");

		Map<String, String> secrets = new VaultClient(tokenConfig("secret/myapp"))
				.readSecrets("secret/myapp");

		assertTrue(secrets.isEmpty());
	}
}
