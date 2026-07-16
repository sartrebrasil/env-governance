package com.example.envgovernance;

import com.example.envgovernance.contract.ContractViolation;
import com.example.envgovernance.contract.EnvContract;
import com.example.envgovernance.contract.VarSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes unitários para {@link ContractRegistry}.
 *
 * @author Sartre Brasil
 * @since 2.2
 */
class ContractRegistryTest {

	@AfterEach
	void tearDown() {
		ContractRegistry.reset();
	}

	@Test
	void estadoInicialEhVazio() {
		assertTrue(ContractRegistry.getContract().isEmpty());
		assertTrue(ContractRegistry.getViolations().isEmpty());
	}

	@Test
	void setContractArmazenaERecupera() {
		EnvContract contract = new EnvContract(List.of(VarSpec.of("X", true)), List.of());
		ContractRegistry.setContract(contract);
		assertSame(contract, ContractRegistry.getContract());
	}

	@Test
	void setNullContractVoltaParaVazio() {
		ContractRegistry.setContract(null);
		assertTrue(ContractRegistry.getContract().isEmpty());
	}

	@Test
	void setViolationsArmazenaERecupera() {
		List<ContractViolation> v = List.of(
				new ContractViolation("X", ContractViolation.Kind.INVALID, "ruim", "val"));
		ContractRegistry.setViolations(v);
		assertSame(1, ContractRegistry.getViolations().size());
	}

	@Test
	void resetLimpaContratoEViolacoes() {
		ContractRegistry.setContract(new EnvContract(List.of(VarSpec.of("X", true)), List.of()));
		ContractRegistry.setViolations(List.of(
				new ContractViolation("X", ContractViolation.Kind.INVALID, "ruim", "val")));

		ContractRegistry.reset();

		assertTrue(ContractRegistry.getContract().isEmpty());
		assertTrue(ContractRegistry.getViolations().isEmpty());
	}
}
