package com.example.envgovernance.contract;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes unitários para {@link PropertiesEnvContractParser}.
 *
 * @author Sartre Brasil
 * @since 2.2
 */
class PropertiesEnvContractParserTest {

	private final PropertiesEnvContractParser parser = new PropertiesEnvContractParser();

	// -------------------------------------------------------------------------
	// supports
	// -------------------------------------------------------------------------

	@Test
	void suportaExtensaoProperties() {
		assertTrue(parser.supports("env-governance.properties"));
		assertTrue(parser.supports("custom.properties"));
		assertFalse(parser.supports("env-governance.yml"));
		assertFalse(parser.supports(null));
	}

	// -------------------------------------------------------------------------
	// Round-trip: spec completa
	// -------------------------------------------------------------------------

	@Test
	void parseiaEspecificacaoCompleta() {
		String content = """
				DB_URL.required=true
				DB_URL.type=url
				DB_URL.description=URL do banco
				DB_URL.sensitive=false
				""";

		EnvContract contract = parse(content);

		assertEquals(1, contract.specs().size());
		VarSpec spec = contract.specFor("DB_URL").orElseThrow();
		assertTrue(spec.required());
		assertEquals("url", spec.type());
		assertEquals("URL do banco", spec.description());
		assertFalse(spec.sensitive());
		assertEquals(1, spec.validators().size());
	}

	@Test
	void parseiaOneOf() {
		String content = """
				MODE.required=true
				MODE.oneOf=token,approle,ldap
				""";

		EnvContract contract = parse(content);
		VarSpec spec = contract.specFor("MODE").orElseThrow();
		assertEquals(1, spec.validators().size());
		assertTrue(spec.validators().get(0).describe().contains("oneOf"));

		// valida o comportamento do validador extraído
		assertTrue(spec.validators().get(0).validate("MODE", "token").valid());
		assertFalse(spec.validators().get(0).validate("MODE", "invalid").valid());
	}

	@Test
	void parseiaMinMax() {
		String content = """
				TIMEOUT.required=false
				TIMEOUT.type=int
				TIMEOUT.min=1
				TIMEOUT.max=300
				""";

		EnvContract contract = parse(content);
		VarSpec spec = contract.specFor("TIMEOUT").orElseThrow();
		// type=int + min + max = 3 validadores
		assertEquals(3, spec.validators().size());
	}

	@Test
	void parseiaRegex() {
		String content = "CODE.regex=[A-Z]{3}-[0-9]+\n";

		EnvContract contract = parse(content);
		VarSpec spec = contract.specFor("CODE").orElseThrow();
		assertEquals(1, spec.validators().size());
		assertTrue(spec.validators().get(0).validate("CODE", "ABC-123").valid());
		assertFalse(spec.validators().get(0).validate("CODE", "abc-123").valid());
	}

	@Test
	void parseiaRequisitoCondicional() {
		String content = """
				requireIf.approle.when=AUTH_METHOD=approle
				requireIf.approle.require=ROLE_ID,SECRET_ID
				""";

		EnvContract contract = parse(content);

		assertEquals(1, contract.conditionals().size());
		ConditionalRequirement req = contract.conditionals().get(0);
		assertEquals(List.of("ROLE_ID", "SECRET_ID"), req.requiredWhenTrue());
		assertTrue(req.condition().test(java.util.Map.of("AUTH_METHOD", "approle")));
		assertFalse(req.condition().test(java.util.Map.of("AUTH_METHOD", "token")));
	}

	@Test
	void arquivoVazioRetornaContratoVazio() {
		EnvContract contract = parse("");
		assertTrue(contract.isEmpty());
	}

	// -------------------------------------------------------------------------
	// Erros específicos (nunca catch(Exception))
	// -------------------------------------------------------------------------

	@Test
	void chaveInvalidaLancaParseException() {
		EnvContractParseException ex = assertThrows(EnvContractParseException.class,
				() -> parse("SEMPONTOIGUAL\n"));
		assertTrue(ex.getMessage().contains("VARNAME.atributo"));
	}

	@Test
	void tipoDesconhecidoLancaParseException() {
		EnvContractParseException ex = assertThrows(EnvContractParseException.class,
				() -> parse("X.type=inexistente\n"));
		assertTrue(ex.getMessage().contains("tipo desconhecido"));
	}

	@Test
	void requireIfSemWhenLancaParseException() {
		EnvContractParseException ex = assertThrows(EnvContractParseException.class,
				() -> parse("requireIf.grupo.require=A,B\n"));
		assertEquals("env-governance.properties", ex.getResource());
		assertTrue(ex.getMessage().contains("'when'"));
	}

	@Test
	void requireIfSemRequireLancaParseException() {
		assertThrows(EnvContractParseException.class,
				() -> parse("requireIf.grupo.when=K=v\n"));
	}

	@Test
	void requireIfAtributoDesconhecidoLancaParseException() {
		assertThrows(EnvContractParseException.class,
				() -> parse("requireIf.grupo.unknown=x\n"));
	}

	@Test
	void requiredInvalidoLancaParseException() {
		assertThrows(EnvContractParseException.class,
				() -> parse("X.required=maybe\n"));
	}

	@Test
	void minNaoNumericoLancaParseException() {
		assertThrows(EnvContractParseException.class,
				() -> parse("X.min=abc\n"));
	}

	// -------------------------------------------------------------------------
	// Helper
	// -------------------------------------------------------------------------

	private EnvContract parse(String content) {
		InputStream in = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
		return parser.parse("env-governance.properties", in);
	}
}
