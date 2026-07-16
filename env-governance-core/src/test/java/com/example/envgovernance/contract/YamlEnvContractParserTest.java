package com.example.envgovernance.contract;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes unitários para {@link YamlEnvContractParser}.
 *
 * @author Sartre Brasil
 * @since 2.2
 */
class YamlEnvContractParserTest {

	private final YamlEnvContractParser parser = new YamlEnvContractParser();

	// -------------------------------------------------------------------------
	// supports
	// -------------------------------------------------------------------------

	@Test
	void suportaYmlEYaml() {
		assertTrue(parser.supports("env-governance.yml"));
		assertTrue(parser.supports("custom.yaml"));
		assertFalse(parser.supports("env-governance.properties"));
		assertFalse(parser.supports(null));
	}

	// -------------------------------------------------------------------------
	// Round-trip
	// -------------------------------------------------------------------------

	@Test
	void parseiaEspecificacaoCompleta() {
		String yaml = """
				vars:
				  DB_URL:
				    required: true
				    type: url
				    description: "URL do banco"
				    sensitive: false
				""";

		EnvContract contract = parse(yaml);

		assertEquals(1, contract.specs().size());
		VarSpec spec = contract.specFor("DB_URL").orElseThrow();
		assertTrue(spec.required());
		assertEquals("url", spec.type());
		assertEquals("URL do banco", spec.description());
		assertEquals(1, spec.validators().size());
	}

	@Test
	void parseiaOneOfComoLista() {
		String yaml = """
				vars:
				  MODE:
				    required: true
				    oneOf: [token, approle]
				""";

		EnvContract contract = parse(yaml);
		VarSpec spec = contract.specFor("MODE").orElseThrow();
		ValueValidator v = spec.validators().get(0);
		assertTrue(v.validate("MODE", "token").valid());
		assertFalse(v.validate("MODE", "ldap").valid());
	}

	@Test
	void parseiaRequisitoCondicional() {
		String yaml = """
				conditionals:
				  - when: "AUTH_METHOD=approle"
				    require:
				      - ROLE_ID
				      - SECRET_ID
				""";

		EnvContract contract = parse(yaml);

		assertEquals(1, contract.conditionals().size());
		ConditionalRequirement req = contract.conditionals().get(0);
		assertEquals(2, req.requiredWhenTrue().size());
		assertTrue(req.condition().test(Map.of("AUTH_METHOD", "approle")));
		assertFalse(req.condition().test(Map.of("AUTH_METHOD", "token")));
	}

	@Test
	void yamlVazioRetornaContratoVazio() {
		assertTrue(parse("").isEmpty());
		assertTrue(parse("# só comentário\n").isEmpty());
	}

	// -------------------------------------------------------------------------
	// Erros específicos
	// -------------------------------------------------------------------------

	@Test
	void raizNaoMapeamentoLancaParseException() {
		assertThrows(EnvContractParseException.class, () -> parse("- item\n"));
	}

	@Test
	void tipoDesconhecidoLancaParseException() {
		String yaml = "vars:\n  X:\n    type: inexistente\n";
		assertThrows(EnvContractParseException.class, () -> parse(yaml));
	}

	@Test
	void conditionalSemWhenLancaParseException() {
		String yaml = "conditionals:\n  - require: [A, B]\n";
		EnvContractParseException ex = assertThrows(
				EnvContractParseException.class, () -> parse(yaml));
		assertTrue(ex.getMessage().contains("'when'"));
	}

	@Test
	void conditionalSemRequireLancaParseException() {
		String yaml = "conditionals:\n  - when: K=v\n";
		assertThrows(EnvContractParseException.class, () -> parse(yaml));
	}

	// -------------------------------------------------------------------------
	// Helper
	// -------------------------------------------------------------------------

	private EnvContract parse(String content) {
		InputStream in = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
		return parser.parse("env-governance.yml", in);
	}
}
