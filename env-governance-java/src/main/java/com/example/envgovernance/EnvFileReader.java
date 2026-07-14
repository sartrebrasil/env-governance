package com.example.envgovernance;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Lê arquivos de configuração de variáveis de ambiente sem dependências externas.
 *
 * @author Sartre Brasil
 * @since 2.0
 */
public final class EnvFileReader {

	private EnvFileReader() {}

	/**
	 * Lê um arquivo no formato {@code .env} (KEY=VALUE, comentários com {@code #},
	 * valores entre aspas simples ou duplas).
	 *
	 * @param file caminho do arquivo
	 * @return mapa imutável das variáveis lidas
	 * @throws IOException se o arquivo não puder ser lido
	 */
	public static Map<String, String> readDotEnv(Path file) throws IOException {
		Map<String, String> result = new LinkedHashMap<>();
		try (BufferedReader reader = Files.newBufferedReader(file)) {
			String line;
			while ((line = reader.readLine()) != null) {
				line = line.strip();
				if (line.isEmpty() || line.startsWith("#")) continue;
				int eq = line.indexOf('=');
				if (eq <= 0) continue;
				String key = line.substring(0, eq).strip();
				String value = unquote(line.substring(eq + 1).strip());
				result.put(key, value);
			}
		}
		return Map.copyOf(result);
	}

	/**
	 * Lê um arquivo no formato {@code .properties} ({@link java.util.Properties} padrão).
	 *
	 * @param file caminho do arquivo
	 * @return mapa imutável das variáveis lidas
	 * @throws IOException se o arquivo não puder ser lido
	 */
	public static Map<String, String> readProperties(Path file) throws IOException {
		Properties props = new Properties();
		try (InputStream in = Files.newInputStream(file)) {
			props.load(in);
		}
		Map<String, String> result = new LinkedHashMap<>();
		for (String key : props.stringPropertyNames()) {
			result.put(key, props.getProperty(key));
		}
		return Map.copyOf(result);
	}

	private static String unquote(String value) {
		if (value.length() >= 2) {
			char first = value.charAt(0);
			char last  = value.charAt(value.length() - 1);
			if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
				return value.substring(1, value.length() - 1);
			}
		}
		return value;
	}
}
