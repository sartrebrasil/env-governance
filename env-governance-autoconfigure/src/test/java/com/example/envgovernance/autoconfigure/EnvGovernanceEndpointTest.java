package com.example.envgovernance.autoconfigure;

import com.example.envgovernance.ContractRegistry;
import com.example.envgovernance.contract.ContractViolation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes unitários para {@link EnvGovernanceEndpoint}.
 *
 * <p>Usa registros estáticos em estado inicial vazio; viola&ccedil;ões de contrato são
 * injetadas via {@link ContractRegistry} antes de cada teste relevante.
 *
 * @author Sartre Brasil
 * @since 2.2
 */
class EnvGovernanceEndpointTest {

	private final EnvGovernanceEndpoint endpoint = new EnvGovernanceEndpoint();

	@AfterEach
	void tearDown() {
		ContractRegistry.reset();
	}

	// -------------------------------------------------------------------------
	// Baseline: sem violações
	// -------------------------------------------------------------------------

	@Test
	void semViolacoesGapsInvalidEstaVazio() {
		Map<String, Object> report = endpoint.report();
		List<?> invalid = gapsInvalid(report);
		assertTrue(invalid.isEmpty());
	}

	// -------------------------------------------------------------------------
	// Violações INVALID aparecem em gaps.invalid
	// -------------------------------------------------------------------------

	@Test
	void violacaoInvalidAparecemEmGapsInvalid() {
		ContractRegistry.setViolations(List.of(
				new ContractViolation("BAD_PORT", ContractViolation.Kind.INVALID,
						"BAD_PORT: porta inválida: abc", "abc")));

		Map<String, Object> report = endpoint.report();
		List<Map<String, String>> invalid = gapsInvalid(report);

		assertEquals(1, invalid.size());
		assertEquals("BAD_PORT", invalid.get(0).get("name"));
		assertEquals("abc", invalid.get(0).get("value"));
		assertTrue(invalid.get(0).get("message").contains("BAD_PORT"));
	}

	@Test
	void violacoesOrdenadosPorNome() {
		ContractRegistry.setViolations(List.of(
				new ContractViolation("Z_VAR", ContractViolation.Kind.INVALID, "Z_VAR ruim", "x"),
				new ContractViolation("A_VAR", ContractViolation.Kind.INVALID, "A_VAR ruim", "y")));

		List<Map<String, String>> invalid = gapsInvalid(endpoint.report());

		assertEquals(2, invalid.size());
		assertEquals("A_VAR", invalid.get(0).get("name"));
		assertEquals("Z_VAR", invalid.get(1).get("name"));
	}

	@Test
	void violacoesMissingNaoAparecemEmGapsInvalid() {
		ContractRegistry.setViolations(List.of(
				new ContractViolation("MISSING_VAR", ContractViolation.Kind.MISSING,
						"MISSING_VAR: ausente", null)));

		List<Map<String, String>> invalid = gapsInvalid(endpoint.report());
		assertTrue(invalid.isEmpty());
	}

	@Test
	void valorNullMapeadoParaStringVazia() {
		ContractRegistry.setViolations(List.of(
				new ContractViolation("NULL_VAL", ContractViolation.Kind.INVALID,
						"NULL_VAL: inválido", null)));

		List<Map<String, String>> invalid = gapsInvalid(endpoint.report());
		assertEquals(1, invalid.size());
		assertEquals("", invalid.get(0).get("value"));
	}

	// -------------------------------------------------------------------------
	// Helper
	// -------------------------------------------------------------------------

	@SuppressWarnings("unchecked")
	private List<Map<String, String>> gapsInvalid(Map<String, Object> report) {
		Map<String, Object> gaps = (Map<String, Object>) report.get("gaps");
		return (List<Map<String, String>>) gaps.get("invalid");
	}
}
