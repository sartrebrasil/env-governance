package com.example.envgovernance.contract;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes unitários para os {@link ValueValidator}s embutidos em {@link Validators}.
 *
 * @author Sartre Brasil
 * @since 2.2
 */
class ValidatorsTest {

	// -------------------------------------------------------------------------
	// integer
	// -------------------------------------------------------------------------

	@Test
	void integerDeveAceitarInteiroERejeitarTexto() {
		assertTrue(Validators.integer().validate("N", "42").valid());
		assertTrue(Validators.integer().validate("N", "  -7 ").valid());
		assertFalse(Validators.integer().validate("N", "abc").valid());
		assertFalse(Validators.integer().validate("N", "3.14").valid());
	}

	// -------------------------------------------------------------------------
	// port
	// -------------------------------------------------------------------------

	@Test
	void portDeveAceitarIntervaloValido() {
		assertTrue(Validators.port().validate("P", "1").valid());
		assertTrue(Validators.port().validate("P", "8080").valid());
		assertTrue(Validators.port().validate("P", "65535").valid());
	}

	@Test
	void portDeveRejeitarForaDoIntervalo() {
		assertFalse(Validators.port().validate("P", "0").valid());
		assertFalse(Validators.port().validate("P", "65536").valid());
		assertFalse(Validators.port().validate("P", "-1").valid());
		assertFalse(Validators.port().validate("P", "xyz").valid());
	}

	// -------------------------------------------------------------------------
	// url
	// -------------------------------------------------------------------------

	@Test
	void urlDeveAceitarUrlAbsolutaERejeitarSemEsquema() {
		assertTrue(Validators.url().validate("U", "https://vault.example.com").valid());
		assertTrue(Validators.url().validate("U", "jdbc:postgresql://localhost:5432/db").valid());
		assertFalse(Validators.url().validate("U", "example.com/path").valid());
		assertFalse(Validators.url().validate("U", "sem esquema").valid());
	}

	// -------------------------------------------------------------------------
	// bool
	// -------------------------------------------------------------------------

	@Test
	void boolDeveAceitarTrueFalseIndependenteDeCase() {
		assertTrue(Validators.bool().validate("B", "true").valid());
		assertTrue(Validators.bool().validate("B", "FALSE").valid());
		assertFalse(Validators.bool().validate("B", "yes").valid());
		assertFalse(Validators.bool().validate("B", "1").valid());
	}

	// -------------------------------------------------------------------------
	// nonEmpty
	// -------------------------------------------------------------------------

	@Test
	void nonEmptyDeveRejeitarVazioEBranco() {
		assertTrue(Validators.nonEmpty().validate("V", "x").valid());
		assertFalse(Validators.nonEmpty().validate("V", "").valid());
		assertFalse(Validators.nonEmpty().validate("V", "   ").valid());
	}

	// -------------------------------------------------------------------------
	// oneOf
	// -------------------------------------------------------------------------

	@Test
	void oneOfDeveAceitarSomenteValoresDoConjunto() {
		ValueValidator v = Validators.oneOf("token", "approle");
		assertTrue(v.validate("M", "token").valid());
		assertTrue(v.validate("M", "approle").valid());
		assertFalse(v.validate("M", "ldap").valid());
		assertFalse(v.validate("M", "TOKEN").valid());
	}

	// -------------------------------------------------------------------------
	// regex
	// -------------------------------------------------------------------------

	@Test
	void regexDeveExigirMatchCompleto() {
		ValueValidator v = Validators.regex("[a-z]+");
		assertTrue(v.validate("R", "abc").valid());
		assertFalse(v.validate("R", "abc123").valid());
	}

	// -------------------------------------------------------------------------
	// min / max
	// -------------------------------------------------------------------------

	@Test
	void minMaxDeveCompararLimites() {
		assertTrue(Validators.min(10).validate("N", "10").valid());
		assertFalse(Validators.min(10).validate("N", "9").valid());
		assertTrue(Validators.max(100).validate("N", "100").valid());
		assertFalse(Validators.max(100).validate("N", "101").valid());
		assertFalse(Validators.min(10).validate("N", "nan").valid());
	}

	// -------------------------------------------------------------------------
	// describe
	// -------------------------------------------------------------------------

	@Test
	void describeDeveExporRotuloEstavel() {
		assertEquals("port", Validators.port().describe());
		assertEquals("url", Validators.url().describe());
		assertTrue(Validators.oneOf("a", "b").describe().contains("oneOf"));
	}
}
