package com.example.envgovernance.contract;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes unitários para o agregado {@link EnvContract} e sua engine de avaliação.
 *
 * @author Sartre Brasil
 * @since 2.2
 */
class EnvContractTest {

	// -------------------------------------------------------------------------
	// Contrato vazio
	// -------------------------------------------------------------------------

	@Test
	void contratoVazioNuncaProduzViolacoes() {
		EnvContract contract = EnvContract.empty();
		assertTrue(contract.isEmpty());
		assertTrue(contract.validate(Map.of()).isEmpty());
		assertTrue(contract.validate(Map.of("QUALQUER", "valor")).isEmpty());
	}

	// -------------------------------------------------------------------------
	// Presença
	// -------------------------------------------------------------------------

	@Test
	void deveReportarObrigatoriaAusenteComoMissing() {
		EnvContract contract = new EnvContract(List.of(VarSpec.of("DB_URL", true)), List.of());

		List<ContractViolation> violations = contract.validate(Map.of());

		assertEquals(1, violations.size());
		assertEquals("DB_URL", violations.get(0).name());
		assertEquals(ContractViolation.Kind.MISSING, violations.get(0).kind());
	}

	@Test
	void naoDeveReportarObrigatoriaPresente() {
		EnvContract contract = new EnvContract(List.of(VarSpec.of("DB_URL", true)), List.of());
		assertTrue(contract.validate(Map.of("DB_URL", "jdbc:postgresql://h/db")).isEmpty());
	}

	@Test
	void opcionalAusenteNaoProduzViolacao() {
		EnvContract contract = new EnvContract(List.of(VarSpec.of("DB_USER", false)), List.of());
		assertTrue(contract.validate(Map.of()).isEmpty());
	}

	// -------------------------------------------------------------------------
	// Validação de valor
	// -------------------------------------------------------------------------

	@Test
	void deveReportarValorInvalidoQuandoPresente() {
		VarSpec port = new VarSpec("PORT", false, List.of(Validators.port()), "", false, "port");
		EnvContract contract = new EnvContract(List.of(port), List.of());

		List<ContractViolation> violations = contract.validate(Map.of("PORT", "abc"));

		assertEquals(1, violations.size());
		assertEquals(ContractViolation.Kind.INVALID, violations.get(0).kind());
		assertEquals("abc", violations.get(0).value());
	}

	@Test
	void validadoresNaoRodamParaVariavelAusente() {
		VarSpec port = new VarSpec("PORT", false, List.of(Validators.port()), "", false, "port");
		EnvContract contract = new EnvContract(List.of(port), List.of());
		// ausente e opcional → nenhum INVALID, nenhum MISSING
		assertTrue(contract.validate(Map.of()).isEmpty());
	}

	@Test
	void obrigatoriaAusenteReportaMissingMasNaoInvalid() {
		VarSpec port = new VarSpec("PORT", true, List.of(Validators.port()), "", false, "port");
		EnvContract contract = new EnvContract(List.of(port), List.of());

		List<ContractViolation> violations = contract.validate(Map.of());

		assertEquals(1, violations.size());
		assertEquals(ContractViolation.Kind.MISSING, violations.get(0).kind());
	}

	// -------------------------------------------------------------------------
	// Requisitos condicionais
	// -------------------------------------------------------------------------

	@Test
	void condicaoSatisfeitaPromoveVariavelParaObrigatoria() {
		ConditionalRequirement req = ConditionalRequirement.whenEquals(
				"AUTH_METHOD", "approle", "ROLE_ID", "SECRET_ID");
		EnvContract contract = new EnvContract(List.of(), List.of(req));

		List<ContractViolation> violations = contract.validate(Map.of("AUTH_METHOD", "approle"));

		assertEquals(2, violations.size());
		assertTrue(violations.stream().allMatch(v -> v.kind() == ContractViolation.Kind.CONDITIONAL_MISSING));
		assertEquals(List.of("ROLE_ID", "SECRET_ID"),
				violations.stream().map(ContractViolation::name).toList());
	}

	@Test
	void condicaoNaoSatisfeitaNaoExigeNada() {
		ConditionalRequirement req = ConditionalRequirement.whenEquals(
				"AUTH_METHOD", "approle", "ROLE_ID", "SECRET_ID");
		EnvContract contract = new EnvContract(List.of(), List.of(req));

		assertTrue(contract.validate(Map.of("AUTH_METHOD", "token")).isEmpty());
	}

	@Test
	void condicaoSatisfeitaComVariaveisPresentesNaoViola() {
		ConditionalRequirement req = ConditionalRequirement.whenEquals(
				"AUTH_METHOD", "approle", "ROLE_ID", "SECRET_ID");
		EnvContract contract = new EnvContract(List.of(), List.of(req));

		Map<String, String> env = Map.of("AUTH_METHOD", "approle", "ROLE_ID", "r", "SECRET_ID", "s");
		assertTrue(contract.validate(env).isEmpty());
	}

	@Test
	void effectiveRequiredNamesUneEstaticoECondicional() {
		VarSpec dbUrl = VarSpec.of("DB_URL", true);
		ConditionalRequirement req = ConditionalRequirement.whenEquals(
				"AUTH_METHOD", "approle", "ROLE_ID");
		EnvContract contract = new EnvContract(List.of(dbUrl), List.of(req));

		assertEquals(Set.of("DB_URL"), contract.effectiveRequiredNames(Map.of()));
		assertEquals(Set.of("DB_URL", "ROLE_ID"),
				contract.effectiveRequiredNames(Map.of("AUTH_METHOD", "approle")));
	}

	// -------------------------------------------------------------------------
	// merge
	// -------------------------------------------------------------------------

	@Test
	void mergeComVazioRetornaMesmaInstancia() {
		EnvContract contract = new EnvContract(List.of(VarSpec.of("A", true)), List.of());
		assertSame(contract, contract.merge(EnvContract.empty()));
		assertSame(contract, EnvContract.empty().merge(contract));
	}

	@Test
	void mergeDaPrecedenciaAoReceptorEmConflitoDeNome() {
		VarSpec required = new VarSpec("X", true, List.of(), "receptor", false, "");
		VarSpec optional = new VarSpec("X", false, List.of(), "outro", false, "");
		EnvContract receptor = new EnvContract(List.of(required), List.of());
		EnvContract outro = new EnvContract(List.of(optional), List.of());

		EnvContract merged = receptor.merge(outro);

		assertEquals(1, merged.specs().size());
		assertTrue(merged.specFor("X").orElseThrow().required());
		assertEquals("receptor", merged.specFor("X").orElseThrow().description());
	}

	@Test
	void mergeConcatenaCondicionais() {
		ConditionalRequirement a = ConditionalRequirement.whenEquals("M", "a", "VA");
		ConditionalRequirement b = ConditionalRequirement.whenEquals("M", "b", "VB");
		EnvContract merged = new EnvContract(List.of(), List.of(a)).merge(new EnvContract(List.of(), List.of(b)));
		assertEquals(2, merged.conditionals().size());
	}

	// -------------------------------------------------------------------------
	// Ordenação determinística
	// -------------------------------------------------------------------------

	@Test
	void violacoesSaoOrdenadasPorNome() {
		EnvContract contract = new EnvContract(
				List.of(VarSpec.of("Z_VAR", true), VarSpec.of("A_VAR", true)), List.of());

		List<String> names = contract.validate(Map.of()).stream().map(ContractViolation::name).toList();

		assertEquals(List.of("A_VAR", "Z_VAR"), names);
		assertFalse(names.isEmpty());
	}
}
