package com.example.envgovernance.contract;

import java.io.InputStream;

/**
 * SPI para parsers de contratos declarativos de configuração.
 * <p>
 * Implementações são descobertas via {@link java.util.ServiceLoader} usando o arquivo
 * {@code META-INF/services/com.example.envgovernance.contract.EnvContractParser}.
 * <p>
 * O módulo {@code env-governance-java} registra {@code PropertiesEnvContractParser}
 * (suporte a {@code env-governance.properties}, zero-dep). O módulo
 * {@code env-governance-core} registra um parser YAML, disponível apenas quando
 * Spring Boot está no classpath.
 *
 * @author Sartre Brasil
 * @since 2.2
 * @see EnvContractLoader
 */
public interface EnvContractParser {

	/**
	 * Retorna {@code true} se este parser é capaz de interpretar o arquivo com o nome
	 * informado. A decisão é baseada apenas no nome — não há leitura de conteúdo.
	 *
	 * @param filename nome do arquivo (ex.: {@code "env-governance.properties"})
	 */
	boolean supports(String filename);

	/**
	 * Interpreta o stream e retorna o contrato extraído.
	 *
	 * @param filename nome do recurso (usado em mensagens de erro)
	 * @param input    stream do conteúdo do arquivo; o chamador é responsável por fechá-lo
	 * @return contrato extraído; nunca {@code null} — retorna {@link EnvContract#empty()} se
	 *         o arquivo existir mas não declarar nenhuma variável
	 * @throws EnvContractParseException se o conteúdo for inválido
	 */
	EnvContract parse(String filename, InputStream input) throws EnvContractParseException;
}
