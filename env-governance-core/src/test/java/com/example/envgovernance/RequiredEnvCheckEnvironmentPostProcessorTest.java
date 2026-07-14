package com.example.envgovernance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes unitários para {@link RequiredEnvCheckEnvironmentPostProcessor}.
 * <p>
 * Popula {@link DeclaredVarsRegistry} diretamente, simulando o que o
 * {@link DeclaredVarsScanner} faria ao escanear um {@code application.yml}.
 *
 * @author Sartre Brasil
 * @since 1.0
 */
class RequiredEnvCheckEnvironmentPostProcessorTest {

	private final RequiredEnvCheckEnvironmentPostProcessor processor =
			new RequiredEnvCheckEnvironmentPostProcessor();

	@AfterEach
	void tearDown() {
		DeclaredVarsRegistry.reset();
	}

	/** Cria uma var explícita obrigatória (${VAR} sem default). */
	private DeclaredVarsRegistry.DeclaredVar required(String envVar, String key) {
		return new DeclaredVarsRegistry.DeclaredVar(envVar, key, "application.yml", true, false, true);
	}

	/** Cria uma var explícita opcional (${VAR:default}). */
	private DeclaredVarsRegistry.DeclaredVar optional(String envVar, String key) {
		return new DeclaredVarsRegistry.DeclaredVar(envVar, key, "application.yml", true, true, true);
	}

	/** Cria uma var derivada de chave (relaxed binding, nunca falha — o YAML tem valor). */
	private DeclaredVarsRegistry.DeclaredVar keyDerived(String envVar, String key) {
		return new DeclaredVarsRegistry.DeclaredVar(envVar, key, "application.yml", false, true, true);
	}

	// -------------------------------------------------------------------------
	// casos de falha
	// -------------------------------------------------------------------------

	@Test
	void deveLancarExcecaoQuandoVariavelObrigatoriaEstaAusente() {
		DeclaredVarsRegistry.register(required("DB_PASSWORD", "spring.datasource.password"));

		MissingRequiredEnvironmentVariablesException ex = assertThrows(
				MissingRequiredEnvironmentVariablesException.class,
				() -> processor.postProcessEnvironment(new StandardEnvironment(), new SpringApplication()));

		assertTrue(ex.getMessage().contains("DB_PASSWORD"));
		assertEquals(1, ex.getMissingVarNames().size());
		assertEquals("DB_PASSWORD", ex.getMissingVarNames().get(0));
	}

	@Test
	void deveMencionarTodasVariaveisAusentesNaMensagem() {
		DeclaredVarsRegistry.register(required("VAR_A", "prop.a"));
		DeclaredVarsRegistry.register(required("VAR_B", "prop.b"));

		MissingRequiredEnvironmentVariablesException ex = assertThrows(
				MissingRequiredEnvironmentVariablesException.class,
				() -> processor.postProcessEnvironment(new StandardEnvironment(), new SpringApplication()));

		assertTrue(ex.getMessage().contains("VAR_A"));
		assertTrue(ex.getMessage().contains("VAR_B"));
		assertEquals(2, ex.getMissingVarNames().size());
	}

	// -------------------------------------------------------------------------
	// casos de sucesso
	// -------------------------------------------------------------------------

	@Test
	void devePassarQuandoVariavelObrigatoriaEstaPresente() {
		DeclaredVarsRegistry.register(required("DB_PASSWORD", "spring.datasource.password"));

		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().addFirst(new MapPropertySource("test", Map.of("DB_PASSWORD", "secret")));

		assertDoesNotThrow(() -> processor.postProcessEnvironment(env, new SpringApplication()));
	}

	@Test
	void deveIgnorarVarsComDefault() {
		// ${VAR:default} — opcional, não deve causar falha mesmo ausente no SO
		DeclaredVarsRegistry.register(optional("REDIS_PORT", "spring.data.redis.port"));

		assertDoesNotThrow(() -> processor.postProcessEnvironment(new StandardEnvironment(), new SpringApplication()));
	}

	@Test
	void deveIgnorarVarsDerivadaDaChave() {
		// key-derived — nunca é "required" no sentido do placeholder; o YAML tem valor
		DeclaredVarsRegistry.register(keyDerived("SPRING_APPLICATION_NAME", "spring.application.name"));

		assertDoesNotThrow(() -> processor.postProcessEnvironment(new StandardEnvironment(), new SpringApplication()));
	}

	@Test
	void devePassarComRegistryVazio() {
		assertDoesNotThrow(() -> processor.postProcessEnvironment(new StandardEnvironment(), new SpringApplication()));
	}

	// -------------------------------------------------------------------------
	// desabilitação via configuração
	// -------------------------------------------------------------------------

	@Test
	void deveIgnorarQuandoFailOnMissingEstaDesabilitado() {
		DeclaredVarsRegistry.register(required("REQUIRED_VAR", "my.prop"));

		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().addFirst(
				new MapPropertySource("test", Map.of("env.governance.fail-on-missing", "false")));

		assertDoesNotThrow(() -> processor.postProcessEnvironment(env, new SpringApplication()));
	}

	@Test
	void deveIgnorarQuandoGovernanceEstaDesabilitado() {
		DeclaredVarsRegistry.register(required("REQUIRED_VAR", "my.prop"));

		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().addFirst(
				new MapPropertySource("test", Map.of("env.governance.enabled", "false")));

		assertDoesNotThrow(() -> processor.postProcessEnvironment(env, new SpringApplication()));
	}
}
