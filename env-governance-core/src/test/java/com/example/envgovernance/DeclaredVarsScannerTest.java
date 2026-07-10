package com.example.envgovernance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes unitários para {@link DeclaredVarsScanner}.
 *
 * @author Sartre Brasil
 * @since 1.0
 */
class DeclaredVarsScannerTest {

	private static final String APP_SOURCE =
			"Config resource 'class path resource [application.yml]' via location 'optional:classpath:/'";

	private final DeclaredVarsScanner scanner = new DeclaredVarsScanner();

	@AfterEach
	void tearDown() {
		DeclaredVarsRegistry.reset();
	}

	// -------------------------------------------------------------------------
	// Relaxed binding (derivado da chave)
	// -------------------------------------------------------------------------

	@Test
	void deveRegistrarVarDerivadaDaChaveComValorLiteral() {
		scan(Map.of("spring.application.name", "minha-app"));

		DeclaredVarsRegistry.DeclaredVar var = DeclaredVarsRegistry.getAll().get("SPRING_APPLICATION_NAME");
		assertNotNull(var);
		assertEquals("spring.application.name", var.propertyKey());
		assertFalse(var.explicit());
		assertTrue(var.hasDefault());
		assertTrue(var.hasYamlValue(), "valor literal 'minha-app' deve resultar em hasYamlValue=true");
	}

	@Test
	void deveRegistrarVarDerivadaDaChaveSemValor() {
		// property com valor null/vazio — gap de configuração
		Map<String, Object> props = new java.util.HashMap<>();
		props.put("spring.datasource.url", null);
		scan(props);

		DeclaredVarsRegistry.DeclaredVar var = DeclaredVarsRegistry.getAll().get("SPRING_DATASOURCE_URL");
		assertNotNull(var);
		assertFalse(var.explicit());
		assertFalse(var.hasYamlValue(), "valor null deve resultar em hasYamlValue=false");
	}

	@Test
	void deveConverterHifenParaUnderscore() {
		scan(Map.of("server.ssl.key-store", "/certs/ks.p12"));

		assertTrue(DeclaredVarsRegistry.getAll().containsKey("SERVER_SSL_KEY_STORE"));
	}

	@Test
	void deveRegistrarSourceFile() {
		scan(Map.of("my.prop", "value"));

		assertEquals("application.yml", DeclaredVarsRegistry.getAll().get("MY_PROP").sourceFile());
	}

	// -------------------------------------------------------------------------
	// Placeholder explícito no valor
	// -------------------------------------------------------------------------

	@Test
	void deveRegistrarDoisNomesDiferentesParaUmaPropriedade() {
		// chave  → BRIDGE_POLLER_MAX_MESSAGES_PER_POLL  (relaxed binding)
		// valor  → BRIDGE_MAX_MESSAGES_PER_POLL          (placeholder — nome diferente)
		scan(Map.of("bridge.poller.max-messages-per-poll", "${BRIDGE_MAX_MESSAGES_PER_POLL:100}"));

		Map<String, DeclaredVarsRegistry.DeclaredVar> all = DeclaredVarsRegistry.getAll();

		DeclaredVarsRegistry.DeclaredVar keyDerived = all.get("BRIDGE_POLLER_MAX_MESSAGES_PER_POLL");
		assertNotNull(keyDerived, "var derivada da chave deve existir");
		assertFalse(keyDerived.explicit());
		assertTrue(keyDerived.hasYamlValue(), "valor não-vazio → hasYamlValue=true");

		DeclaredVarsRegistry.DeclaredVar explicit = all.get("BRIDGE_MAX_MESSAGES_PER_POLL");
		assertNotNull(explicit, "placeholder explícito deve existir");
		assertTrue(explicit.explicit());
		assertTrue(explicit.hasDefault(), "placeholder com ':100' tem hasDefault=true");
		assertTrue(explicit.hasYamlValue(), "placeholder explícito sempre tem hasYamlValue=true");
		assertEquals("bridge.poller.max-messages-per-poll", explicit.propertyKey());
	}

	@Test
	void deveRegistrarPlaceholderSemDefaultComoRequired() {
		scan(Map.of("spring.datasource.password", "${DB_PASSWORD}"));

		DeclaredVarsRegistry.DeclaredVar var = DeclaredVarsRegistry.getAll().get("DB_PASSWORD");
		assertNotNull(var);
		assertTrue(var.explicit());
		assertFalse(var.hasDefault(), "placeholder sem ':default' tem hasDefault=false");
	}

	@Test
	void deveRegistrarMultiplosPlaceholdersNoMesmoValor() {
		scan(Map.of("my.dsn", "${DB_HOST:localhost}:${DB_PORT:5432}"));

		Map<String, DeclaredVarsRegistry.DeclaredVar> all = DeclaredVarsRegistry.getAll();
		assertTrue(all.containsKey("DB_HOST"));
		assertTrue(all.containsKey("DB_PORT"));
		assertTrue(all.get("DB_HOST").hasDefault());
		assertTrue(all.get("DB_PORT").hasDefault());
	}

	@Test
	void explicitDeveVencerKeyDerivedQuandoMesmoNome() {
		// server.port → SERVER_PORT (key-derived)
		// ${SERVER_PORT:8080} → SERVER_PORT (explicit) — mesmo nome, explicit vence
		scan(Map.of("server.port", "${SERVER_PORT:8080}"));

		DeclaredVarsRegistry.DeclaredVar var = DeclaredVarsRegistry.getAll().get("SERVER_PORT");
		assertNotNull(var);
		assertTrue(var.explicit(), "explicit deve ter precedência sobre key-derived");
		assertTrue(var.hasDefault());
	}

	// -------------------------------------------------------------------------
	// Filtro de property sources
	// -------------------------------------------------------------------------

	@Test
	void deveIgnorarPropertySourcesForaDaAplicacao() {
		// StandardEnvironment só tem systemProperties e systemEnvironment — não são app-config
		scanner.postProcessEnvironment(new StandardEnvironment(), new SpringApplication());

		assertTrue(DeclaredVarsRegistry.getAll().isEmpty());
	}

	@Test
	void deveIgnorarQuandoGovernanceEstaDesabilitado() {
		StandardEnvironment env = envWith(APP_SOURCE, Map.of("some.prop", "value"));
		env.getPropertySources().addFirst(
				new MapPropertySource("test-disable", Map.of("env.governance.enabled", "false")));

		scanner.postProcessEnvironment(env, new SpringApplication());

		assertTrue(DeclaredVarsRegistry.getAll().isEmpty());
	}

	// -------------------------------------------------------------------------
	// toEnvVarName
	// -------------------------------------------------------------------------

	@Test
	void toEnvVarNameDeveConverterCorretamente() {
		assertEquals("SPRING_APPLICATION_NAME", DeclaredVarsScanner.toEnvVarName("spring.application.name"));
		assertEquals("SERVER_SSL_KEY_STORE",    DeclaredVarsScanner.toEnvVarName("server.ssl.key-store"));
		assertEquals("MY_PROP",                 DeclaredVarsScanner.toEnvVarName("my.prop"));
		assertEquals("BRIDGE_MAX_MESSAGES_PER_POLL",
				DeclaredVarsScanner.toEnvVarName("BRIDGE_MAX_MESSAGES_PER_POLL"));
	}

	// -------------------------------------------------------------------------
	// helpers
	// -------------------------------------------------------------------------

	private void scan(Map<String, Object> props) {
		scanner.postProcessEnvironment(envWith(APP_SOURCE, props), new SpringApplication());
	}

	private StandardEnvironment envWith(String sourceName, Map<String, Object> props) {
		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().addFirst(new MapPropertySource(sourceName, props));
		return env;
	}
}
