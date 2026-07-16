package com.example.envgovernance.contract;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Parser de contrato declarativo no formato {@code .properties} (JDK puro, zero-dep).
 * <p>
 * Cada variável é descrita por chaves no padrão {@code VARNAME.atributo=valor}; requisitos
 * condicionais usam {@code requireIf.GRUPO.when} / {@code requireIf.GRUPO.require}.
 *
 * <h3>Formato</h3>
 * <pre>{@code
 * # Especificações de variáveis
 * DB_URL.required=true
 * DB_URL.type=url
 * DB_URL.description=URL de conexão com o banco de dados
 *
 * SERVER_PORT.required=false
 * SERVER_PORT.type=port
 *
 * AUTH_METHOD.required=true
 * AUTH_METHOD.oneOf=token,approle
 *
 * API_KEY.required=true
 * API_KEY.sensitive=true
 *
 * RETRY_COUNT.required=false
 * RETRY_COUNT.type=int
 * RETRY_COUNT.min=1
 * RETRY_COUNT.max=10
 *
 * # Requisitos condicionais
 * requireIf.approle.when=AUTH_METHOD=approle
 * requireIf.approle.require=VAULT_ROLE_ID,VAULT_SECRET_ID
 * }</pre>
 *
 * <h3>Atributos de especificação</h3>
 * <ul>
 *   <li>{@code required} — {@code true}/{@code false} (default: {@code false})</li>
 *   <li>{@code type} — {@code port|url|int|boolean|non-empty} — adiciona o validador correspondente</li>
 *   <li>{@code description} — texto livre para relatórios</li>
 *   <li>{@code sensitive} — {@code true}/{@code false} (default: auto por nome)</li>
 *   <li>{@code oneOf} — valores permitidos separados por vírgula</li>
 *   <li>{@code regex} — expressão regular (match total)</li>
 *   <li>{@code min} — inteiro mínimo (long)</li>
 *   <li>{@code max} — inteiro máximo (long)</li>
 *   <li>{@code nonEmpty} — {@code true} para exigir valor não-branco</li>
 * </ul>
 *
 * @author Sartre Brasil
 * @since 2.2
 * @see EnvContractParser
 */
public final class PropertiesEnvContractParser implements EnvContractParser {

	private static final String REQUIRE_IF_PREFIX = "requireIf.";

	@Override
	public boolean supports(String filename) {
		return filename != null && filename.endsWith(".properties");
	}

	@Override
	public EnvContract parse(String filename, InputStream input) throws EnvContractParseException {
		Properties props = new Properties();
		try {
			props.load(input);
		} catch (IOException e) {
			throw new EnvContractParseException(filename, "erro de I/O ao ler arquivo", e);
		}

		Map<String, Map<String, String>> varAttrs = new LinkedHashMap<>();
		Map<String, Map<String, String>> conditionalGroups = new LinkedHashMap<>();

		for (String rawKey : props.stringPropertyNames()) {
			String value = props.getProperty(rawKey).strip();
			if (rawKey.startsWith(REQUIRE_IF_PREFIX)) {
				parseConditionalEntry(rawKey, value, conditionalGroups, filename);
			} else {
				parseVarEntry(rawKey, value, varAttrs, filename);
			}
		}

		List<VarSpec> specs = buildSpecs(varAttrs, filename);
		List<ConditionalRequirement> conditionals = buildConditionals(conditionalGroups, filename);
		return new EnvContract(specs, conditionals);
	}

	private void parseVarEntry(String rawKey,
	                           String value,
	                           Map<String, Map<String, String>> varAttrs,
	                           String filename) throws EnvContractParseException {
		int dot = rawKey.indexOf('.');
		if (dot <= 0) {
			throw new EnvContractParseException(filename,
					"chave inválida (esperado VARNAME.atributo): " + rawKey);
		}
		String varName = rawKey.substring(0, dot).strip();
		String attr = rawKey.substring(dot + 1).strip();
		if (varName.isEmpty() || attr.isEmpty()) {
			throw new EnvContractParseException(filename,
					"chave inválida (nome ou atributo vazio): " + rawKey);
		}
		varAttrs.computeIfAbsent(varName, k -> new LinkedHashMap<>()).put(attr, value);
	}

	private void parseConditionalEntry(String rawKey,
	                                   String value,
	                                   Map<String, Map<String, String>> groups,
	                                   String filename) throws EnvContractParseException {
		// rawKey = "requireIf.GRUPO.when" ou "requireIf.GRUPO.require"
		String rest = rawKey.substring(REQUIRE_IF_PREFIX.length());
		int dot = rest.indexOf('.');
		if (dot <= 0) {
			throw new EnvContractParseException(filename,
					"chave de requisito condicional inválida (esperado requireIf.GRUPO.when|require): " + rawKey);
		}
		String group = rest.substring(0, dot).strip();
		String attr = rest.substring(dot + 1).strip();
		if (!attr.equals("when") && !attr.equals("require")) {
			throw new EnvContractParseException(filename,
					"atributo desconhecido em requireIf (esperado 'when' ou 'require'): " + rawKey);
		}
		groups.computeIfAbsent(group, k -> new LinkedHashMap<>()).put(attr, value);
	}

	private List<VarSpec> buildSpecs(Map<String, Map<String, String>> varAttrs,
	                                 String filename) throws EnvContractParseException {
		List<VarSpec> specs = new ArrayList<>();
		for (Map.Entry<String, Map<String, String>> entry : varAttrs.entrySet()) {
			String varName = entry.getKey();
			Map<String, String> attrs = entry.getValue();

			boolean required = parseBoolean(attrs.getOrDefault("required", "false"), "required", varName, filename);
			String description = attrs.getOrDefault("description", "");
			boolean sensitive = parseBoolean(attrs.getOrDefault("sensitive", "false"), "sensitive", varName, filename);
			String type = attrs.getOrDefault("type", "");

			List<ValueValidator> validators = new ArrayList<>();
			addTypeValidator(type, validators, varName, filename);

			if (attrs.containsKey("oneOf")) {
				String[] values = attrs.get("oneOf").split(",", -1);
				for (int i = 0; i < values.length; i++) {
					values[i] = values[i].strip();
				}
				validators.add(Validators.oneOf(values));
			}
			if (attrs.containsKey("regex")) {
				validators.add(Validators.regex(attrs.get("regex")));
			}
			if (attrs.containsKey("min")) {
				validators.add(Validators.min(parseLong(attrs.get("min"), "min", varName, filename)));
			}
			if (attrs.containsKey("max")) {
				validators.add(Validators.max(parseLong(attrs.get("max"), "max", varName, filename)));
			}
			if ("true".equalsIgnoreCase(attrs.getOrDefault("nonEmpty", "false"))) {
				validators.add(Validators.nonEmpty());
			}

			specs.add(new VarSpec(varName, required, validators, description, sensitive, type));
		}
		return specs;
	}

	private void addTypeValidator(String type,
	                              List<ValueValidator> validators,
	                              String varName,
	                              String filename) throws EnvContractParseException {
		if (type.isEmpty()) return;
		switch (type) {
			case "port"     -> validators.add(Validators.port());
			case "url"      -> validators.add(Validators.url());
			case "int"      -> validators.add(Validators.integer());
			case "boolean"  -> validators.add(Validators.bool());
			case "non-empty" -> validators.add(Validators.nonEmpty());
			default -> throw new EnvContractParseException(filename,
					"tipo desconhecido para " + varName + ": " + type
							+ " (esperado: port, url, int, boolean, non-empty)");
		}
	}

	private List<ConditionalRequirement> buildConditionals(Map<String, Map<String, String>> groups,
	                                                       String filename) throws EnvContractParseException {
		List<ConditionalRequirement> conditionals = new ArrayList<>();
		for (Map.Entry<String, Map<String, String>> entry : groups.entrySet()) {
			String group = entry.getKey();
			Map<String, String> attrs = entry.getValue();

			String when = attrs.get("when");
			String require = attrs.get("require");
			if (when == null) {
				throw new EnvContractParseException(filename,
						"requireIf." + group + " não tem 'when'");
			}
			if (require == null) {
				throw new EnvContractParseException(filename,
						"requireIf." + group + " não tem 'require'");
			}

			int eq = when.indexOf('=');
			if (eq <= 0) {
				throw new EnvContractParseException(filename,
						"requireIf." + group + ".when inválido (esperado CHAVE=valor): " + when);
			}
			String key = when.substring(0, eq).strip();
			String expectedValue = when.substring(eq + 1).strip();
			String[] required = require.split(",", -1);
			for (int i = 0; i < required.length; i++) {
				required[i] = required[i].strip();
			}
			conditionals.add(ConditionalRequirement.whenEquals(key, expectedValue, required));
		}
		return conditionals;
	}

	private boolean parseBoolean(String value, String attr, String varName, String filename)
			throws EnvContractParseException {
		if ("true".equalsIgnoreCase(value)) return true;
		if ("false".equalsIgnoreCase(value)) return false;
		throw new EnvContractParseException(filename,
				"valor inválido para " + varName + "." + attr + " (esperado true/false): " + value);
	}

	private long parseLong(String value, String attr, String varName, String filename)
			throws EnvContractParseException {
		try {
			return Long.parseLong(value.strip());
		} catch (NumberFormatException e) {
			throw new EnvContractParseException(filename,
					"valor inválido para " + varName + "." + attr + " (esperado inteiro): " + value);
		}
	}
}
