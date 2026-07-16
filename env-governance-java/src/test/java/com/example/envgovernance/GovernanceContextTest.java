package com.example.envgovernance;

import com.example.envgovernance.contract.ConditionalRequirement;
import com.example.envgovernance.contract.ContractViolation;
import com.example.envgovernance.contract.VarSpec;
import com.example.envgovernance.spi.EnvVarSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes unitários para {@link GovernanceContext} e a API fluente de contrato.
 * <p>
 * Nomes de variáveis usam sufixos improváveis ({@code _XYZ}) para não colidir com o
 * ambiente real do S.O. Valores presentes são injetados via {@link EnvVarSource} de teste,
 * já que {@code System.getenv()} não é mutável.
 *
 * @author Sartre Brasil
 * @since 2.2
 */
class GovernanceContextTest {

	// -------------------------------------------------------------------------
	// Regressão: comportamento sem validação de valor idêntico ao anterior
	// -------------------------------------------------------------------------

	@Test
	void semContratoNaoHaGaps() {
		GovernanceResult result = GovernanceContext.builder()
				.withoutServiceLoaderDiscovery()
				.build()
				.check();

		assertFalse(result.hasGaps());
		assertTrue(result.missingRequired().isEmpty());
		assertTrue(result.violations().isEmpty());
	}

	@Test
	void obrigatoriaAusenteApareceEmMissingRequired() {
		GovernanceResult result = GovernanceContext.builder()
				.withoutServiceLoaderDiscovery()
				.require("ENV_GOV_ABSENT_XYZ")
				.build()
				.check();

		assertTrue(result.hasGaps());
		assertEquals(List.of("ENV_GOV_ABSENT_XYZ"), result.missingRequired());
	}

	@Test
	void verifyLancaMissingParaAusenciaPura() {
		GovernanceContext context = GovernanceContext.builder()
				.withoutServiceLoaderDiscovery()
				.require("ENV_GOV_ABSENT_XYZ")
				.build();

		MissingRequiredEnvironmentVariablesException ex = assertThrows(
				MissingRequiredEnvironmentVariablesException.class, context::verify);
		assertEquals(List.of("ENV_GOV_ABSENT_XYZ"), ex.getMissingVarNames());
	}

	// -------------------------------------------------------------------------
	// Validação de valor
	// -------------------------------------------------------------------------

	@Test
	void valorInvalidoProduzViolacaoInvalid() {
		GovernanceResult result = GovernanceContext.builder()
				.withoutServiceLoaderDiscovery()
				.addSource(mapSource("test-src", Map.of("ENV_GOV_PORT_XYZ", "abc")))
				.require("ENV_GOV_PORT_XYZ").asPort()
				.build()
				.check();

		assertTrue(result.hasGaps());
		assertTrue(result.missingRequired().isEmpty());
		assertEquals(1, result.violations().size());
		assertEquals(ContractViolation.Kind.INVALID, result.violations().get(0).kind());
	}

	@Test
	void valorValidoNaoProduzViolacao() {
		GovernanceResult result = GovernanceContext.builder()
				.withoutServiceLoaderDiscovery()
				.addSource(mapSource("test-src", Map.of("ENV_GOV_PORT_XYZ", "8080")))
				.require("ENV_GOV_PORT_XYZ").asPort()
				.build()
				.check();

		assertFalse(result.hasGaps());
	}

	@Test
	void verifyLancaContractValidationParaValorInvalido() {
		GovernanceContext context = GovernanceContext.builder()
				.withoutServiceLoaderDiscovery()
				.addSource(mapSource("test-src", Map.of("ENV_GOV_PORT_XYZ", "abc")))
				.require("ENV_GOV_PORT_XYZ").asPort()
				.build();

		EnvContractValidationException ex = assertThrows(
				EnvContractValidationException.class, context::verify);
		assertEquals(1, ex.getViolations().size());
	}

	// -------------------------------------------------------------------------
	// Requisitos condicionais
	// -------------------------------------------------------------------------

	@Test
	void requireIfPromoveVariavelQuandoCondicaoSatisfeita() {
		GovernanceResult result = GovernanceContext.builder()
				.withoutServiceLoaderDiscovery()
				.addSource(mapSource("test-src", Map.of("ENV_GOV_MODE_XYZ", "approle")))
				.requireIf("ENV_GOV_MODE_XYZ=approle", "ENV_GOV_ROLE_XYZ", "ENV_GOV_SECRET_XYZ")
				.build()
				.check();

		assertTrue(result.hasGaps());
		assertEquals(List.of("ENV_GOV_ROLE_XYZ", "ENV_GOV_SECRET_XYZ"), result.missingRequired());
		assertTrue(result.violations().stream()
				.allMatch(v -> v.kind() == ContractViolation.Kind.CONDITIONAL_MISSING));
	}

	@Test
	void requireIfNaoExigeQuandoCondicaoFalsa() {
		GovernanceResult result = GovernanceContext.builder()
				.withoutServiceLoaderDiscovery()
				.addSource(mapSource("test-src", Map.of("ENV_GOV_MODE_XYZ", "token")))
				.requireIf("ENV_GOV_MODE_XYZ=approle", "ENV_GOV_ROLE_XYZ")
				.build()
				.check();

		assertFalse(result.hasGaps());
	}

	@Test
	void requireIfComExpressaoMalformadaLancaIllegalArgument() {
		assertThrows(IllegalArgumentException.class, () -> GovernanceContext.builder()
				.requireIf("sem-igual", "X"));
	}

	// -------------------------------------------------------------------------
	// Cadeia fluente
	// -------------------------------------------------------------------------

	@Test
	void cadeiaFluenteMisturaRequireOptionalEValidadores() {
		GovernanceResult result = GovernanceContext.builder()
				.withoutServiceLoaderDiscovery()
				.addSource(mapSource("test-src", Map.of(
						"ENV_GOV_URL_XYZ", "https://example.com",
						"ENV_GOV_LEVEL_XYZ", "debug")))
				.require("ENV_GOV_URL_XYZ").asUrl().nonEmpty()
				.optional("ENV_GOV_LEVEL_XYZ").oneOf("info", "debug", "warn")
				.build()
				.check();

		assertFalse(result.hasGaps());
	}

	// -------------------------------------------------------------------------
	// Contribuições de fontes (contributedSpecs / contributedConditionals)
	// -------------------------------------------------------------------------

	@Test
	void sourceContributedSpecsMergedNoContrato() {
		GovernanceResult result = GovernanceContext.builder()
				.withoutServiceLoaderDiscovery()
				.addSource(contributingSource("src-contrib",
						List.of(VarSpec.of("ENV_GOV_CONTRIB_XYZ", true)),
						List.of()))
				.build()
				.check();

		// A spec foi contribuída pela fonte → ausência detectada como missing
		assertTrue(result.hasGaps());
		assertTrue(result.missingRequired().contains("ENV_GOV_CONTRIB_XYZ"));
	}

	@Test
	void builderVenceSourceContribuicaoEmConflito() {
		// Builder declara optional; fonte contribui required para o mesmo nome
		GovernanceResult result = GovernanceContext.builder()
				.withoutServiceLoaderDiscovery()
				.optional("ENV_GOV_CONTRIB_XYZ")       // builder = não obrigatória
				.addSource(contributingSource("src-contrib",
						List.of(VarSpec.of("ENV_GOV_CONTRIB_XYZ", true)), // fonte = obrigatória
						List.of()))
				.build()
				.check();

		// Builder (receiver) vence: optional não gera missing
		assertFalse(result.hasGaps());
	}

	@Test
	void sourceContributedConditionalsMergedNoContrato() {
		ConditionalRequirement cond = ConditionalRequirement.whenEquals(
				"ENV_GOV_MODE_XYZ", "approle", "ENV_GOV_ROLE_XYZ");

		GovernanceResult result = GovernanceContext.builder()
				.withoutServiceLoaderDiscovery()
				.addSource(mapSource("src-with-env", Map.of("ENV_GOV_MODE_XYZ", "approle")))
				.addSource(contributingSource("src-contrib", List.of(), List.of(cond)))
				.build()
				.check();

		// Condição satisfeita; ENV_GOV_ROLE_XYZ ausente → CONDITIONAL_MISSING
		assertTrue(result.hasGaps());
		assertTrue(result.missingRequired().contains("ENV_GOV_ROLE_XYZ"));
	}

	// -------------------------------------------------------------------------
	// Helper
	// -------------------------------------------------------------------------

	private static EnvVarSource mapSource(String sourceName, Map<String, String> values) {
		return new EnvVarSource() {
			private Set<String> names = Set.of();

			@Override
			public String name() {
				return sourceName;
			}

			@Override
			public boolean isAvailable(Map<String, String> environment) {
				return true;
			}

			@Override
			public Map<String, String> load(Map<String, String> environment) {
				this.names = Set.copyOf(values.keySet());
				return values;
			}

			@Override
			public Set<String> getVarNames() {
				return names;
			}

			@Override
			public int getOrder() {
				return 50;
			}
		};
	}

	private static EnvVarSource contributingSource(String sourceName,
	                                                List<VarSpec> specs,
	                                                List<ConditionalRequirement> conds) {
		return new EnvVarSource() {
			@Override public String name() { return sourceName; }
			@Override public boolean isAvailable(Map<String, String> e) { return true; }
			@Override public Map<String, String> load(Map<String, String> e) { return Map.of(); }
			@Override public Set<String> getVarNames() { return Set.of(); }
			@Override public int getOrder() { return 100; }
			@Override public List<VarSpec> contributedSpecs() { return specs; }
			@Override public List<ConditionalRequirement> contributedConditionals() { return conds; }
		};
	}
}
