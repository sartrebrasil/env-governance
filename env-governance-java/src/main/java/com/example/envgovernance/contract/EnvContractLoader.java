package com.example.envgovernance.contract;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Carrega o contrato declarativo de configuração a partir do classpath.
 * <p>
 * Percorre uma lista de nomes de recursos em ordem de preferência e retorna o contrato do
 * primeiro recurso encontrado para o qual exista um {@link EnvContractParser} compatível.
 * Retorna {@link EnvContract#empty()} silenciosamente se nenhum recurso for encontrado —
 * a presença do arquivo é opcional; sem ele, o comportamento da aplicação é idêntico ao
 * anterior à introdução do contrato declarativo.
 * <p>
 * Parsers são descobertos via {@link ServiceLoader} (arquivo
 * {@code META-INF/services/com.example.envgovernance.contract.EnvContractParser}).
 * O módulo {@code env-governance-java} registra {@link PropertiesEnvContractParser};
 * {@code env-governance-core} registra um parser YAML quando Spring Boot estiver presente.
 *
 * <h3>Localização padrão dos recursos</h3>
 * <ol>
 *   <li>{@code env-governance.properties} — suportado em qualquer ambiente (zero-dep).</li>
 *   <li>{@code env-governance.yml} — suportado quando o parser YAML do core está no classpath.</li>
 * </ol>
 *
 * @author Sartre Brasil
 * @since 2.2
 * @see EnvContractParser
 */
public final class EnvContractLoader {

	static final List<String> DEFAULT_LOCATIONS = List.of(
			"env-governance.properties",
			"env-governance.yml"
	);

	private EnvContractLoader() {
	}

	/**
	 * Carrega o contrato usando o {@link ClassLoader} de contexto do thread corrente.
	 *
	 * @return contrato carregado, ou {@link EnvContract#empty()} se nenhum arquivo for encontrado
	 * @throws EnvContractParseException se um arquivo for encontrado mas não puder ser interpretado
	 */
	public static EnvContract loadFromClasspath() {
		return loadFromClasspath(Thread.currentThread().getContextClassLoader());
	}

	/**
	 * Carrega o contrato usando o {@link ClassLoader} informado.
	 *
	 * @param classLoader classloader para resolução de recursos
	 * @return contrato carregado, ou {@link EnvContract#empty()} se nenhum arquivo for encontrado
	 * @throws EnvContractParseException se um arquivo for encontrado mas não puder ser interpretado
	 */
	public static EnvContract loadFromClasspath(ClassLoader classLoader) {
		List<EnvContractParser> parsers = discoverParsers(classLoader);
		for (String filename : DEFAULT_LOCATIONS) {
			EnvContract contract = tryLoad(filename, parsers, classLoader);
			if (contract != null) {
				return contract;
			}
		}
		return EnvContract.empty();
	}

	/**
	 * Carrega o contrato a partir de um recurso com nome específico, sem percorrer os locais padrão.
	 *
	 * @param filename    nome do recurso classpath (ex.: {@code "my-contract.properties"})
	 * @param classLoader classloader para resolução
	 * @return contrato carregado, ou {@link EnvContract#empty()} se o recurso não existir
	 * @throws EnvContractParseException se o recurso existir mas não puder ser interpretado
	 */
	public static EnvContract loadFromClasspath(String filename, ClassLoader classLoader) {
		List<EnvContractParser> parsers = discoverParsers(classLoader);
		EnvContract contract = tryLoad(filename, parsers, classLoader);
		return contract != null ? contract : EnvContract.empty();
	}

	private static EnvContract tryLoad(String filename,
	                                   List<EnvContractParser> parsers,
	                                   ClassLoader classLoader) {
		EnvContractParser parser = parsers.stream()
				.filter(p -> p.supports(filename))
				.findFirst()
				.orElse(null);
		if (parser == null) return null;

		InputStream stream = classLoader.getResourceAsStream(filename);
		if (stream == null) return null;

		try (InputStream in = stream) {
			return parser.parse(filename, in);
		} catch (EnvContractParseException e) {
			throw e;
		} catch (Exception e) {
			// stream só pode lançar IOException no close; relançar como parse exception
			throw new EnvContractParseException(filename, "erro ao fechar stream do recurso", e);
		}
	}

	private static List<EnvContractParser> discoverParsers(ClassLoader classLoader) {
		List<EnvContractParser> parsers = new ArrayList<>();
		ServiceLoader.load(EnvContractParser.class, classLoader)
				.forEach(parsers::add);
		return parsers;
	}
}
