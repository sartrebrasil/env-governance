package com.example.envgovernance.contract;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fábrica de {@link ValueValidator}s embutidos, implementados apenas com o JDK 21 —
 * sem nenhuma dependência de framework, coerente com o módulo {@code env-governance-java}.
 *
 * @author Sartre Brasil
 * @since 2.2
 * @see ValueValidator
 */
public final class Validators {

	private Validators() {
	}

	/** Exige um inteiro decimal ({@link Long#parseLong(String)}). */
	public static ValueValidator integer() {
		return named("int", (name, value) -> {
			if (parseLongOrNull(value) == null) {
				return ValidationResult.invalid(name + ": valor não é um inteiro válido (\"" + value + "\")");
			}
			return ValidationResult.ok();
		});
	}

	/** Exige um número de porta TCP/UDP válido (1–65535). */
	public static ValueValidator port() {
		return named("port", (name, value) -> {
			Long parsed = parseLongOrNull(value);
			if (parsed == null || parsed < 1 || parsed > 65535) {
				return ValidationResult.invalid(name + ": porta fora do intervalo 1–65535 (\"" + value + "\")");
			}
			return ValidationResult.ok();
		});
	}

	/** Exige uma URL absoluta (com esquema), validada por {@link URI}. */
	public static ValueValidator url() {
		return named("url", (name, value) -> {
			try {
				URI uri = new URI(value);
				if (uri.getScheme() == null || uri.getScheme().isBlank()) {
					return ValidationResult.invalid(name + ": URL sem esquema (\"" + value + "\")");
				}
				return ValidationResult.ok();
			} catch (URISyntaxException e) {
				return ValidationResult.invalid(name + ": URL malformada (\"" + value + "\")");
			}
		});
	}

	/** Exige um booleano textual: {@code true} ou {@code false} (case-insensitive). */
	public static ValueValidator bool() {
		return named("boolean", (name, value) -> {
			if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
				return ValidationResult.ok();
			}
			return ValidationResult.invalid(name + ": esperado true/false (\"" + value + "\")");
		});
	}

	/** Exige um valor não vazio (após {@code trim}). */
	public static ValueValidator nonEmpty() {
		return named("non-empty", (name, value) -> {
			if (value == null || value.isBlank()) {
				return ValidationResult.invalid(name + ": valor não pode ser vazio");
			}
			return ValidationResult.ok();
		});
	}

	/** Exige que o valor esteja no conjunto informado (comparação exata, sensível a maiúsculas). */
	public static ValueValidator oneOf(String... allowed) {
		Set<String> set = new LinkedHashSet<>(Arrays.asList(allowed));
		return named("oneOf" + set, (name, value) -> {
			if (set.contains(value)) {
				return ValidationResult.ok();
			}
			return ValidationResult.invalid(name + ": valor \"" + value + "\" não está em " + set);
		});
	}

	/** Exige que o valor case totalmente com a expressão regular informada. */
	public static ValueValidator regex(String pattern) {
		Pattern compiled = Pattern.compile(pattern);
		return named("regex(" + pattern + ")", (name, value) -> {
			if (compiled.matcher(value).matches()) {
				return ValidationResult.ok();
			}
			return ValidationResult.invalid(name + ": valor \"" + value + "\" não casa com /" + pattern + "/");
		});
	}

	/** Exige um inteiro maior ou igual a {@code bound}. */
	public static ValueValidator min(long bound) {
		return named("min(" + bound + ")", (name, value) -> {
			Long parsed = parseLongOrNull(value);
			if (parsed == null) {
				return ValidationResult.invalid(name + ": valor não é um inteiro válido (\"" + value + "\")");
			}
			if (parsed < bound) {
				return ValidationResult.invalid(name + ": " + parsed + " < mínimo " + bound);
			}
			return ValidationResult.ok();
		});
	}

	/** Exige um inteiro menor ou igual a {@code bound}. */
	public static ValueValidator max(long bound) {
		return named("max(" + bound + ")", (name, value) -> {
			Long parsed = parseLongOrNull(value);
			if (parsed == null) {
				return ValidationResult.invalid(name + ": valor não é um inteiro válido (\"" + value + "\")");
			}
			if (parsed > bound) {
				return ValidationResult.invalid(name + ": " + parsed + " > máximo " + bound);
			}
			return ValidationResult.ok();
		});
	}

	private static Long parseLongOrNull(String value) {
		try {
			return Long.parseLong(value.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Envolve um validador com um rótulo estável para {@link ValueValidator#describe()}. */
	private static ValueValidator named(String description, ValueValidator delegate) {
		return new ValueValidator() {
			@Override
			public ValidationResult validate(String name, String value) {
				return delegate.validate(name, value);
			}

			@Override
			public String describe() {
				return description;
			}
		};
	}
}
