package com.example.envgovernance.contract;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes unitários para {@link EnvContractLoader}.
 *
 * @author Sartre Brasil
 * @since 2.2
 */
class EnvContractLoaderTest {

	// -------------------------------------------------------------------------
	// Ausência de arquivo — retorno silencioso
	// -------------------------------------------------------------------------

	@Test
	void retornaVazioQuandoNenhumArquivoEncontrado() {
		// ClassLoader isolado que não resolve nenhum recurso de contrato
		ClassLoader empty = new ClassLoader(null) {
			@Override
			public java.io.InputStream getResourceAsStream(String name) {
				return null;
			}
		};
		EnvContract contract = EnvContractLoader.loadFromClasspath(empty);
		assertTrue(contract.isEmpty());
	}

	// -------------------------------------------------------------------------
	// Carregamento via classpath real (recurso de teste)
	// -------------------------------------------------------------------------

	@Test
	void carregaArquivoPropertiesDoClasspath() {
		// test-contract.properties está em src/test/resources
		ClassLoader cl = Thread.currentThread().getContextClassLoader();
		EnvContract contract = EnvContractLoader.loadFromClasspath(
				"test-contract.properties", cl);

		assertFalse(contract.isEmpty());
		assertTrue(contract.specFor("TEST_DB_URL").isPresent());
		assertTrue(contract.specFor("TEST_DB_URL").orElseThrow().required());
	}

	// -------------------------------------------------------------------------
	// Sem parser compatível para extensão desconhecida — retorno silencioso
	// -------------------------------------------------------------------------

	@Test
	void retornaVazioQuandoNenhumParserSuportaExtensao() {
		ClassLoader cl = Thread.currentThread().getContextClassLoader();
		// .toml não tem parser registrado
		EnvContract contract = EnvContractLoader.loadFromClasspath("qualquer.toml", cl);
		assertTrue(contract.isEmpty());
	}
}
