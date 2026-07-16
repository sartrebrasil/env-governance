package com.example.envgovernance.contract;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Parser de contrato declarativo no formato YAML (usa SnakeYAML, disponível via Spring Boot).
 *
 * <h3>Formato</h3>
 * <pre>{@code
 * vars:
 *   DB_URL:
 *     required: true
 *     type: url
 *     description: "URL de conexão com o banco de dados"
 *   SERVER_PORT:
 *     type: port
 *   AUTH_METHOD:
 *     required: true
 *     oneOf: [token, approle]
 *   API_KEY:
 *     required: true
 *     sensitive: true
 *   TIMEOUT:
 *     type: int
 *     min: 1
 *     max: 300
 *
 * conditionals:
 *   - when: "AUTH_METHOD=approle"
 *     require: [VAULT_ROLE_ID, VAULT_SECRET_ID]
 * }</pre>
 *
 * @author Sartre Brasil
 * @since 2.2
 * @see EnvContractParser
 */
public final class YamlEnvContractParser implements EnvContractParser {

	@Override
	public boolean supports(String filename) {
		return filename != null && (filename.endsWith(".yml") || filename.endsWith(".yaml"));
	}

	@Override
	@SuppressWarnings("unchecked")
	public EnvContract parse(String filename, InputStream input) throws EnvContractParseException {
		Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
		Object raw;
		try {
			raw = yaml.load(input);
		} catch (Exception e) {
			throw new EnvContractParseException(filename, "YAML inválido: " + e.getMessage(), e);
		}
		if (raw == null) {
			return EnvContract.empty();
		}
		if (!(raw instanceof Map)) {
			throw new EnvContractParseException(filename,
					"estrutura inválida: esperado mapeamento YAML na raiz");
		}
		Map<String, Object> root = (Map<String, Object>) raw;

		List<VarSpec> specs = parseVars(root, filename);
		List<ConditionalRequirement> conditionals = parseConditionals(root, filename);
		return new EnvContract(specs, conditionals);
	}

	@SuppressWarnings("unchecked")
	private List<VarSpec> parseVars(Map<String, Object> root, String filename)
			throws EnvContractParseException {
		Object varsRaw = root.get("vars");
		if (varsRaw == null) {
			return List.of();
		}
		if (!(varsRaw instanceof Map)) {
			throw new EnvContractParseException(filename,
					"'vars' deve ser um mapeamento YAML (nome: atributos)");
		}
		Map<String, Object> vars = (Map<String, Object>) varsRaw;
		List<VarSpec> specs = new ArrayList<>();
		for (Map.Entry<String, Object> entry : vars.entrySet()) {
			String varName = entry.getKey();
			if (!(entry.getValue() instanceof Map)) {
				throw new EnvContractParseException(filename,
						"atributos de '" + varName + "' devem ser um mapeamento YAML");
			}
			Map<String, Object> attrs = (Map<String, Object>) entry.getValue();
			specs.add(buildSpec(varName, attrs, filename));
		}
		return specs;
	}

	@SuppressWarnings("unchecked")
	private VarSpec buildSpec(String varName, Map<String, Object> attrs, String filename)
			throws EnvContractParseException {
		boolean required = bool(attrs, "required", varName, filename, false);
		boolean sensitive = bool(attrs, "sensitive", varName, filename, false);
		String description = str(attrs, "description", varName, filename, "");
		String type = str(attrs, "type", varName, filename, "");
		boolean nonEmpty = bool(attrs, "nonEmpty", varName, filename, false);

		List<ValueValidator> validators = new ArrayList<>();
		addTypeValidator(type, validators, varName, filename);

		if (attrs.containsKey("oneOf")) {
			Object raw = attrs.get("oneOf");
			if (!(raw instanceof List)) {
				throw new EnvContractParseException(filename,
						"'oneOf' de '" + varName + "' deve ser uma lista YAML");
			}
			String[] allowed = ((List<?>) raw).stream()
					.map(Object::toString)
					.toArray(String[]::new);
			validators.add(Validators.oneOf(allowed));
		}
		if (attrs.containsKey("regex")) {
			validators.add(Validators.regex(str(attrs, "regex", varName, filename, "")));
		}
		if (attrs.containsKey("min")) {
			validators.add(Validators.min(longVal(attrs, "min", varName, filename)));
		}
		if (attrs.containsKey("max")) {
			validators.add(Validators.max(longVal(attrs, "max", varName, filename)));
		}
		if (nonEmpty) {
			validators.add(Validators.nonEmpty());
		}
		return new VarSpec(varName, required, validators, description, sensitive, type);
	}

	@SuppressWarnings("unchecked")
	private List<ConditionalRequirement> parseConditionals(Map<String, Object> root, String filename)
			throws EnvContractParseException {
		Object raw = root.get("conditionals");
		if (raw == null) {
			return List.of();
		}
		if (!(raw instanceof List)) {
			throw new EnvContractParseException(filename,
					"'conditionals' deve ser uma lista YAML");
		}
		List<Object> items = (List<Object>) raw;
		List<ConditionalRequirement> conditionals = new ArrayList<>();
		for (int i = 0; i < items.size(); i++) {
			if (!(items.get(i) instanceof Map)) {
				throw new EnvContractParseException(filename,
						"conditional[" + i + "] deve ser um mapeamento YAML");
			}
			Map<String, Object> item = (Map<String, Object>) items.get(i);
			conditionals.add(buildConditional(item, i, filename));
		}
		return conditionals;
	}

	@SuppressWarnings("unchecked")
	private ConditionalRequirement buildConditional(Map<String, Object> item, int idx, String filename)
			throws EnvContractParseException {
		Object whenRaw = item.get("when");
		Object requireRaw = item.get("require");

		if (whenRaw == null) {
			throw new EnvContractParseException(filename,
					"conditional[" + idx + "] não tem 'when'");
		}
		if (requireRaw == null) {
			throw new EnvContractParseException(filename,
					"conditional[" + idx + "] não tem 'require'");
		}
		if (!(requireRaw instanceof List)) {
			throw new EnvContractParseException(filename,
					"conditional[" + idx + "].require deve ser uma lista YAML");
		}
		String when = whenRaw.toString();
		int eq = when.indexOf('=');
		if (eq <= 0) {
			throw new EnvContractParseException(filename,
					"conditional[" + idx + "].when inválido (esperado CHAVE=valor): " + when);
		}
		String key = when.substring(0, eq).strip();
		String expectedValue = when.substring(eq + 1).strip();
		String[] required = ((List<?>) requireRaw).stream()
				.map(Object::toString)
				.toArray(String[]::new);
		return ConditionalRequirement.whenEquals(key, expectedValue, required);
	}

	private void addTypeValidator(String type, List<ValueValidator> validators, String varName,
	                              String filename) throws EnvContractParseException {
		if (type.isEmpty()) return;
		switch (type) {
			case "port"      -> validators.add(Validators.port());
			case "url"       -> validators.add(Validators.url());
			case "int"       -> validators.add(Validators.integer());
			case "boolean"   -> validators.add(Validators.bool());
			case "non-empty" -> validators.add(Validators.nonEmpty());
			default -> throw new EnvContractParseException(filename,
					"tipo desconhecido para " + varName + ": " + type
							+ " (esperado: port, url, int, boolean, non-empty)");
		}
	}

	private boolean bool(Map<String, Object> attrs, String key, String varName,
	                     String filename, boolean defaultValue) throws EnvContractParseException {
		Object raw = attrs.get(key);
		if (raw == null) return defaultValue;
		if (raw instanceof Boolean b) return b;
		String s = raw.toString();
		if ("true".equalsIgnoreCase(s)) return true;
		if ("false".equalsIgnoreCase(s)) return false;
		throw new EnvContractParseException(filename,
				"valor inválido para " + varName + "." + key + " (esperado true/false): " + raw);
	}

	private String str(Map<String, Object> attrs, String key, String varName,
	                   String filename, String defaultValue) {
		Object raw = attrs.get(key);
		return raw != null ? raw.toString() : defaultValue;
	}

	private long longVal(Map<String, Object> attrs, String key, String varName, String filename)
			throws EnvContractParseException {
		Object raw = attrs.get(key);
		if (raw instanceof Number n) return n.longValue();
		try {
			return Long.parseLong(raw.toString().strip());
		} catch (NumberFormatException e) {
			throw new EnvContractParseException(filename,
					"valor inválido para " + varName + "." + key + " (esperado inteiro): " + raw);
		}
	}
}
