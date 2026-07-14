package com.example.envgovernance.vault.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser JSON minimalista para respostas do Vault. Zero dependências externas.
 * <p>
 * Suporta objetos, arrays, strings (com escapes), números, booleanos e null.
 * Valores não-string são ignorados ao construir {@code Map<String,String>}.
 *
 * @author Sartre Brasil
 * @since 2.0
 */
final class VaultJsonParser {

	private final String src;
	private int pos;

	private VaultJsonParser(String src) {
		this.src = src;
	}

	/**
	 * Faz o parse de um objeto JSON raiz e retorna um mapa plano de strings.
	 * Entradas com valor não-string são ignoradas.
	 */
	static Map<String, String> parseStringMap(String json) {
		Map<String, Object> raw = new VaultJsonParser(json.strip()).readObject();
		Map<String, String> result = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : raw.entrySet()) {
			if (entry.getValue() instanceof String s) {
				result.put(entry.getKey(), s);
			}
		}
		return result;
	}

	/**
	 * Extrai o valor de uma cadeia de chaves aninhadas e retorna como mapa de strings.
	 * Ex.: {@code extractNestedObject(json, "data", "data")} para KV v2.
	 */
	@SuppressWarnings("unchecked")
	static Map<String, String> extractNestedObject(String json, String... path) {
		Object current = new VaultJsonParser(json.strip()).readObject();
		for (String key : path) {
			if (!(current instanceof Map<?, ?> map)) return Map.of();
			current = map.get(key);
		}
		if (!(current instanceof Map<?, ?> map)) return Map.of();
		Map<String, String> result = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			if (entry.getKey() instanceof String k && entry.getValue() instanceof String v) {
				result.put(k, v);
			}
		}
		return Map.copyOf(result);
	}

	/**
	 * Extrai um valor string em uma cadeia de chaves aninhadas.
	 * Ex.: {@code extractString(json, "auth", "client_token")}.
	 */
	static String extractString(String json, String... path) {
		Object current = new VaultJsonParser(json.strip()).readObject();
		for (int i = 0; i < path.length - 1; i++) {
			if (!(current instanceof Map<?, ?> map)) return null;
			current = map.get(path[i]);
		}
		if (!(current instanceof Map<?, ?> map)) return null;
		Object val = map.get(path[path.length - 1]);
		return val instanceof String s ? s : null;
	}

	// -------------------------------------------------------------------------
	// parsing recursivo
	// -------------------------------------------------------------------------

	private void skipWs() {
		while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
	}

	private Map<String, Object> readObject() {
		Map<String, Object> result = new LinkedHashMap<>();
		if (pos >= src.length() || src.charAt(pos) != '{') return result;
		pos++;
		skipWs();
		while (pos < src.length() && src.charAt(pos) != '}') {
			if (src.charAt(pos) == ',') { pos++; skipWs(); continue; }
			if (src.charAt(pos) != '"') break;
			String key = readString();
			skipWs();
			if (pos < src.length() && src.charAt(pos) == ':') pos++;
			skipWs();
			result.put(key, readValue());
			skipWs();
		}
		if (pos < src.length()) pos++; // skip '}'
		return result;
	}

	private Object readValue() {
		if (pos >= src.length()) return null;
		return switch (src.charAt(pos)) {
			case '"'           -> readString();
			case '{'           -> readObject();
			case '['           -> readArray();
			default            -> readLiteral();
		};
	}

	private List<Object> readArray() {
		List<Object> list = new ArrayList<>();
		pos++;
		skipWs();
		while (pos < src.length() && src.charAt(pos) != ']') {
			if (src.charAt(pos) == ',') { pos++; skipWs(); continue; }
			list.add(readValue());
			skipWs();
		}
		if (pos < src.length()) pos++; // skip ']'
		return list;
	}

	private String readString() {
		if (pos >= src.length() || src.charAt(pos) != '"') return "";
		pos++;
		StringBuilder sb = new StringBuilder();
		while (pos < src.length()) {
			char c = src.charAt(pos++);
			if (c == '"') break;
			if (c == '\\' && pos < src.length()) {
				char esc = src.charAt(pos++);
				switch (esc) {
					case '"', '\\', '/' -> sb.append(esc);
					case 'n'            -> sb.append('\n');
					case 't'            -> sb.append('\t');
					case 'r'            -> sb.append('\r');
					default             -> sb.append(esc);
				}
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	private Object readLiteral() {
		int start = pos;
		while (pos < src.length()) {
			char c = src.charAt(pos);
			if (c == ',' || c == '}' || c == ']' || Character.isWhitespace(c)) break;
			pos++;
		}
		String lit = src.substring(start, pos);
		return switch (lit) {
			case "null"  -> null;
			case "true"  -> Boolean.TRUE;
			case "false" -> Boolean.FALSE;
			default      -> lit;
		};
	}
}
