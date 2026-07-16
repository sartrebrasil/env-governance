package com.example.envgovernance;

import com.example.envgovernance.contract.EnvContract;
import com.example.envgovernance.contract.VarSpec;
import com.example.envgovernance.contract.Validators;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes unitários para {@link EnvContractValidationEnvironmentPostProcessor}.
 *
 * @author Sartre Brasil
 * @since 2.2
 */
class EnvContractValidationEnvironmentPostProcessorTest {

	private final EnvContractValidationEnvironmentPostProcessor processor =
			new EnvContractValidationEnvironmentPostProcessor();

	@AfterEach
	void tearDown() {
		ContractRegistry.reset();
	}

	// -------------------------------------------------------------------------
	// Sem contrato — zero efeito
	// -------------------------------------------------------------------------

	@Test
	void semContratoNaoLancaEViolationsVazio() {
		// ContractRegistry.getContract() == empty após reset
		assertDoesNotThrow(() -> processor.postProcessEnvironment(
				new StandardEnvironment(), new SpringApplication()));
		assertTrue(ContractRegistry.getViolations().isEmpty());
	}

	// -------------------------------------------------------------------------
	// Valor inválido → lança por default (fail-on-invalid=true)
	// -------------------------------------------------------------------------

	@Test
	void valorInvalidoLancaEnvContractValidationException() {
		VarSpec spec = new VarSpec("MY_PORT", false, List.of(Validators.port()), "", false, "port");
		ContractRegistry.setContract(new EnvContract(List.of(spec), List.of()));

		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().addFirst(
				new MapPropertySource("test", Map.of("MY_PORT", "abc")));

		assertThrows(EnvContractValidationException.class,
				() -> processor.postProcessEnvironment(env, new SpringApplication()));
	}

	// -------------------------------------------------------------------------
	// fail-on-invalid=false → não lança, mas registra violações
	// -------------------------------------------------------------------------

	@Test
	void failOnInvalidFalseNaoLancaMasRegistraViolacoes() {
		VarSpec spec = new VarSpec("MY_PORT", false, List.of(Validators.port()), "", false, "port");
		ContractRegistry.setContract(new EnvContract(List.of(spec), List.of()));

		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
				"MY_PORT", "abc",
				"env.governance.contract.fail-on-invalid", "false")));

		assertDoesNotThrow(() -> processor.postProcessEnvironment(env, new SpringApplication()));
		assertFalse(ContractRegistry.getViolations().isEmpty());
	}

	// -------------------------------------------------------------------------
	// Desabilitação
	// -------------------------------------------------------------------------

	@Test
	void desabilitadoViaSwitchPrincipal() {
		VarSpec spec = new VarSpec("MY_PORT", false, List.of(Validators.port()), "", false, "port");
		ContractRegistry.setContract(new EnvContract(List.of(spec), List.of()));

		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
				"MY_PORT", "abc",
				"env.governance.enabled", "false")));

		assertDoesNotThrow(() -> processor.postProcessEnvironment(env, new SpringApplication()));
	}

	// -------------------------------------------------------------------------
	// Contrato com valor válido → sem violação
	// -------------------------------------------------------------------------

	@Test
	void valorValidoNaoLancaENaoRegistraViolacoes() {
		VarSpec spec = new VarSpec("MY_PORT", false, List.of(Validators.port()), "", false, "port");
		ContractRegistry.setContract(new EnvContract(List.of(spec), List.of()));

		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().addFirst(
				new MapPropertySource("test", Map.of("MY_PORT", "8080")));

		assertDoesNotThrow(() -> processor.postProcessEnvironment(env, new SpringApplication()));
		assertTrue(ContractRegistry.getViolations().isEmpty());
	}
}
