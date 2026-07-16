package com.example.envgovernance.autoconfigure;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes unitários para {@link WellKnownSystemEnvVars}.
 *
 * @author Sartre Brasil
 * @since 2.1
 */
class WellKnownSystemEnvVarsTest {

	// -------------------------------------------------------------------------
	// Nomes exatos
	// -------------------------------------------------------------------------

	@Test
	void deveClassificarVarsPosixConhecidas() {
		assertTrue(WellKnownSystemEnvVars.isSystemVar("PATH", null));
		assertTrue(WellKnownSystemEnvVars.isSystemVar("HOME", null));
		assertTrue(WellKnownSystemEnvVars.isSystemVar("JAVA_HOME", null));
		assertTrue(WellKnownSystemEnvVars.isSystemVar("MAVEN_OPTS", null));
	}

	@Test
	void deveClassificarVarsWindowsIndependenteDeCase() {
		assertTrue(WellKnownSystemEnvVars.isSystemVar("Path", null));
		assertTrue(WellKnownSystemEnvVars.isSystemVar("ProgramData", null));
		assertTrue(WellKnownSystemEnvVars.isSystemVar("USERPROFILE", null));
	}

	@Test
	void deveClassificarProgramFilesX86ViaPrefixo() {
		assertTrue(WellKnownSystemEnvVars.isSystemVar("ProgramFiles(x86)", null));
		assertTrue(WellKnownSystemEnvVars.isSystemVar("CommonProgramFiles(x86)", null));
	}

	// -------------------------------------------------------------------------
	// Prefixos
	// -------------------------------------------------------------------------

	@Test
	void deveClassificarVarsPorPrefixoDeInfraestrutura() {
		assertTrue(WellKnownSystemEnvVars.isSystemVar("LC_ALL", null));
		assertTrue(WellKnownSystemEnvVars.isSystemVar("KUBERNETES_SERVICE_HOST", null));
		assertTrue(WellKnownSystemEnvVars.isSystemVar("GITHUB_ACTIONS", null));
		assertTrue(WellKnownSystemEnvVars.isSystemVar("SUREFIRE_TEST", null));
		assertTrue(WellKnownSystemEnvVars.isSystemVar("PROCESSOR_ARCHITECTURE", null));
	}

	// -------------------------------------------------------------------------
	// Vars de aplicação NÃO devem ser classificadas como ruído
	// -------------------------------------------------------------------------

	@Test
	void naoDeveClassificarVarsDaAplicacao() {
		assertFalse(WellKnownSystemEnvVars.isSystemVar("SPRING_DATASOURCE_URL", null));
		assertFalse(WellKnownSystemEnvVars.isSystemVar("DB_PASSWORD", null));
		assertFalse(WellKnownSystemEnvVars.isSystemVar("MY_APP_FEATURE_FLAG", null));
	}

	// -------------------------------------------------------------------------
	// Padrões fornecidos pelo usuário
	// -------------------------------------------------------------------------

	@Test
	void deveClassificarViaPadraoDeUsuarioComPrefixo() {
		Set<String> patterns = Set.of("ACME_CI_*");
		assertTrue(WellKnownSystemEnvVars.isSystemVar("ACME_CI_BUILD_ID", patterns));
		assertFalse(WellKnownSystemEnvVars.isSystemVar("ACME_APP_URL", patterns));
	}

	@Test
	void deveClassificarViaPadraoDeUsuarioExato() {
		Set<String> patterns = Set.of("RUNNER_NODE_ID");
		assertTrue(WellKnownSystemEnvVars.isSystemVar("RUNNER_NODE_ID", patterns));
		assertTrue(WellKnownSystemEnvVars.isSystemVar("runner-node-id", patterns));
	}
}
