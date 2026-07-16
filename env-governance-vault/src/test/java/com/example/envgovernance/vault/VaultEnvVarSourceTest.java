package com.example.envgovernance.vault;

import com.example.envgovernance.contract.ConditionalRequirement;
import com.example.envgovernance.contract.ContractViolation;
import com.example.envgovernance.contract.EnvContract;
import com.example.envgovernance.contract.VarSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes unitários para {@link VaultEnvVarSource#contributedSpecs()} e
 * {@link VaultEnvVarSource#contributedConditionals()}.
 *
 * <p>Foca no contrato declarado pela fonte — não testa conexão HTTP com o Vault.
 * A cobertura de HTTP está em {@link com.example.envgovernance.vault.http.VaultClientTest}.
 *
 * @author Sartre Brasil
 * @since 2.2
 */
class VaultEnvVarSourceTest {

	private final VaultEnvVarSource source = new VaultEnvVarSource();

	// -------------------------------------------------------------------------
	// contributedSpecs — estrutura
	// -------------------------------------------------------------------------

	@Test
	void contributedSpecsContemVaultAddrEAuthMethod() {
		List<VarSpec> specs = source.contributedSpecs();
		List<String> names = specs.stream().map(VarSpec::name).toList();

		assertTrue(names.contains("VAULT_ADDR"));
		assertTrue(names.contains("VAULT_AUTH_METHOD"));
	}

	@Test
	void vaultAddrEhOpcionalComValidadorUrl() {
		VarSpec spec = specFor("VAULT_ADDR");
		assertFalse(spec.required());
		assertFalse(spec.validators().isEmpty());
	}

	@Test
	void vaultAuthMethodEhOpcionalComValidadorOneOf() {
		VarSpec spec = specFor("VAULT_AUTH_METHOD");
		assertFalse(spec.required());
		// token e approle são valores válidos; qualquer outro falha
		assertTrue(spec.validators().get(0).validate("VAULT_AUTH_METHOD", "token").valid());
		assertTrue(spec.validators().get(0).validate("VAULT_AUTH_METHOD", "approle").valid());
		assertFalse(spec.validators().get(0).validate("VAULT_AUTH_METHOD", "ldap").valid());
	}

	// -------------------------------------------------------------------------
	// contributedSpecs — validação de formato via EnvContract
	// -------------------------------------------------------------------------

	@Test
	void vaultAddrInvalidoGeraViolacaoInvalid() {
		EnvContract contract = new EnvContract(source.contributedSpecs(), List.of());
		List<ContractViolation> violations = contract.validate(Map.of("VAULT_ADDR", "not-a-url"));

		assertEquals(1, violations.size());
		assertEquals(ContractViolation.Kind.INVALID, violations.get(0).kind());
		assertEquals("VAULT_ADDR", violations.get(0).name());
	}

	@Test
	void vaultAddrValidoNaoGeraViolacao() {
		EnvContract contract = new EnvContract(source.contributedSpecs(), List.of());
		List<ContractViolation> violations = contract.validate(Map.of("VAULT_ADDR", "https://vault.example.com"));

		assertTrue(violations.isEmpty());
	}

	@Test
	void vaultAuthMethodInvalidoGeraViolacaoInvalid() {
		EnvContract contract = new EnvContract(source.contributedSpecs(), List.of());
		List<ContractViolation> violations = contract.validate(Map.of("VAULT_AUTH_METHOD", "ldap"));

		assertEquals(1, violations.size());
		assertEquals("VAULT_AUTH_METHOD", violations.get(0).name());
	}

	// -------------------------------------------------------------------------
	// contributedConditionals — AppRole
	// -------------------------------------------------------------------------

	@Test
	void appRoleCondicionalExigeRoleIdESecretIdQuandoAtivo() {
		EnvContract contract = new EnvContract(List.of(), source.contributedConditionals());

		Map<String, String> env = Map.of(
				"VAULT_ADDR", "https://vault.example.com",
				"VAULT_AUTH_METHOD", "approle");
		List<ContractViolation> violations = contract.validate(env);

		List<String> missingNames = violations.stream()
				.filter(v -> v.kind() == ContractViolation.Kind.CONDITIONAL_MISSING)
				.map(ContractViolation::name)
				.sorted()
				.toList();

		assertEquals(List.of("VAULT_ROLE_ID", "VAULT_SECRET_ID"), missingNames);
	}

	@Test
	void appRoleCondicionalNaoDisparaQuandoVaultDesabilitado() {
		EnvContract contract = new EnvContract(List.of(), source.contributedConditionals());

		// VAULT_ADDR ausente → Vault não configurado → sem requirement
		List<ContractViolation> violations = contract.validate(
				Map.of("VAULT_AUTH_METHOD", "approle"));

		assertTrue(violations.isEmpty());
	}

	@Test
	void appRoleCondicionalNaoDisparaComTokenAuth() {
		EnvContract contract = new EnvContract(List.of(), source.contributedConditionals());

		List<ContractViolation> violations = contract.validate(Map.of(
				"VAULT_ADDR",        "https://vault.example.com",
				"VAULT_AUTH_METHOD", "token",
				"VAULT_TOKEN",       "hvs.abc"));

		assertTrue(violations.isEmpty());
	}

	// -------------------------------------------------------------------------
	// contributedConditionals — Token (método padrão)
	// -------------------------------------------------------------------------

	@Test
	void tokenCondicionalExigeVaultTokenQuandoVaultHabilitado() {
		EnvContract contract = new EnvContract(List.of(), source.contributedConditionals());

		// VAULT_AUTH_METHOD ausente → padrão token → VAULT_TOKEN obrigatório
		List<ContractViolation> violations = contract.validate(
				Map.of("VAULT_ADDR", "https://vault.example.com"));

		List<String> missingNames = violations.stream()
				.filter(v -> v.kind() == ContractViolation.Kind.CONDITIONAL_MISSING)
				.map(ContractViolation::name)
				.toList();

		assertEquals(List.of("VAULT_TOKEN"), missingNames);
	}

	@Test
	void tokenCondicionalExigeVaultTokenComMethodExplicito() {
		EnvContract contract = new EnvContract(List.of(), source.contributedConditionals());

		List<ContractViolation> violations = contract.validate(Map.of(
				"VAULT_ADDR",        "https://vault.example.com",
				"VAULT_AUTH_METHOD", "token"));

		List<String> missingNames = violations.stream()
				.filter(v -> v.kind() == ContractViolation.Kind.CONDITIONAL_MISSING)
				.map(ContractViolation::name)
				.toList();

		assertEquals(List.of("VAULT_TOKEN"), missingNames);
	}

	@Test
	void tokenCondicionalNaoDisparaQuandoVaultDesabilitado() {
		EnvContract contract = new EnvContract(List.of(), source.contributedConditionals());

		// VAULT_ADDR ausente → Vault não configurado
		assertTrue(contract.validate(Map.of()).isEmpty());
	}

	// -------------------------------------------------------------------------
	// Helper
	// -------------------------------------------------------------------------

	private VarSpec specFor(String name) {
		return source.contributedSpecs().stream()
				.filter(s -> s.name().equals(name))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Spec não encontrada: " + name));
	}
}
