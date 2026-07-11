package com.example.envgovernance.source;

import com.example.envgovernance.spi.EnvVarSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes unitários para {@link EnvVarSourceLoaderPostProcessor}.
 * <p>
 * Usa uma subclasse com {@link EnvVarSourceLoaderPostProcessor#loadSources} sobrescrito para
 * injetar implementações de stub sem necessidade de manipulação de classloader.
 *
 * @author Sartre Brasil
 * @since 1.1
 */
class EnvVarSourceLoaderPostProcessorTest {

	@AfterEach
	void tearDown() {
		EnvVarSourceRegistry.reset();
	}

	// -------------------------------------------------------------------------
	// helpers
	// -------------------------------------------------------------------------

	/** Loader que retorna fontes fixas em vez de ler do ServiceLoader. */
	private EnvVarSourceLoaderPostProcessor loaderWith(EnvVarSource... sources) {
		return new EnvVarSourceLoaderPostProcessor() {
			@Override
			protected List<EnvVarSource> loadSources(ClassLoader cl) {
				return List.of(sources);
			}
		};
	}

	private StandardEnvironment emptyEnv() {
		return new StandardEnvironment();
	}

	private StandardEnvironment envWithProp(String key, Object value) {
		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().addFirst(new MapPropertySource("test", Map.of(key, value)));
		return env;
	}

	/** Fonte disponível que não injeta variáveis (tipo SystemEnvVarSource). */
	private EnvVarSource noopSource(String name, int order, Set<String> varNames) {
		return new EnvVarSource() {
			@Override public String name() { return name; }
			@Override public boolean isAvailable(Map<String, String> e) { return true; }
			@Override public Map<String, String> load(Map<String, String> e) { return Map.of(); }
			@Override public Set<String> getVarNames() { return varNames; }
			@Override public int getOrder() { return order; }
		};
	}

	/** Fonte que injeta variáveis no ambiente. */
	private EnvVarSource injectingSource(String name, int order, Map<String, String> secrets) {
		return new EnvVarSource() {
			private final Set<String> names = Set.copyOf(secrets.keySet());
			@Override public String name() { return name; }
			@Override public boolean isAvailable(Map<String, String> e) { return true; }
			@Override public Map<String, String> load(Map<String, String> e) { return secrets; }
			@Override public Set<String> getVarNames() { return names; }
			@Override public int getOrder() { return order; }
		};
	}

	/** Fonte indisponível (isAvailable=false). */
	private EnvVarSource unavailableSource(String name) {
		return new EnvVarSource() {
			@Override public String name() { return name; }
			@Override public boolean isAvailable(Map<String, String> e) { return false; }
			@Override public Map<String, String> load(Map<String, String> e) { return Map.of(); }
			@Override public Set<String> getVarNames() { return Set.of(); }
			@Override public int getOrder() { return 0; }
		};
	}

	/** Fonte que lança exceção no load(). */
	private EnvVarSource failingSource(String name) {
		return new EnvVarSource() {
			@Override public String name() { return name; }
			@Override public boolean isAvailable(Map<String, String> e) { return true; }
			@Override public Map<String, String> load(Map<String, String> e) {
				throw new RuntimeException("conexão recusada");
			}
			@Override public Set<String> getVarNames() { return Set.of(); }
			@Override public int getOrder() { return 0; }
		};
	}

	// -------------------------------------------------------------------------
	// registro e disponibilidade
	// -------------------------------------------------------------------------

	@Test
	void deveFonteDisponvelSemPropertySourceSerRegistrada() {
		EnvVarSource source = noopSource("system-env", 0, Set.of("HOME", "PATH"));

		loaderWith(source).postProcessEnvironment(emptyEnv(), new SpringApplication());

		List<EnvVarSource> active = EnvVarSourceRegistry.getActiveSources();
		assertEquals(1, active.size());
		assertEquals("system-env", active.get(0).name());
	}

	@Test
	void deveFonteIndisponivelSerIgnoradaENaoRegistrada() {
		EnvVarSource unavailable = unavailableSource("vault");

		loaderWith(unavailable).postProcessEnvironment(emptyEnv(), new SpringApplication());

		assertTrue(EnvVarSourceRegistry.getActiveSources().isEmpty());
	}

	// -------------------------------------------------------------------------
	// injeção de PropertySource
	// -------------------------------------------------------------------------

	@Test
	void deveFonteComVariaveisInjetarSegredosNoAmbiente() {
		EnvVarSource vault = injectingSource("vault", 10, Map.of("DB_PASSWORD", "secret123"));
		StandardEnvironment env = emptyEnv();

		loaderWith(vault).postProcessEnvironment(env, new SpringApplication());

		assertEquals("secret123", env.getProperty("DB_PASSWORD"));
	}

	@Test
	void deveInjetarPropertySourceAposSystemEnvironment() {
		EnvVarSource vault = injectingSource("vault", 10, Map.of("MY_VAR", "from-vault"));
		StandardEnvironment env = emptyEnv();

		loaderWith(vault).postProcessEnvironment(env, new SpringApplication());

		// systemEnvironment deve ainda existir e estar antes do vault
		assertTrue(env.getPropertySources().contains("systemEnvironment"));
		assertTrue(env.getPropertySources().contains("vault"));

		int systemIdx = -1, vaultIdx = -1, i = 0;
		for (PropertySource<?> ps : env.getPropertySources()) {
			if ("systemEnvironment".equals(ps.getName())) systemIdx = i;
			if ("vault".equals(ps.getName()))             vaultIdx = i;
			i++;
		}
		assertTrue(systemIdx < vaultIdx,
				"systemEnvironment deve ter posição anterior ao vault (maior prioridade)");
	}

	@Test
	void deveVarDoSoSubrescreverVaultQuandoAmbosFornecem() {
		StandardEnvironment env = emptyEnv();
		env.getPropertySources().addFirst(
				new MapPropertySource("systemEnvironment", Map.of("MY_VAR", "from-os")));

		EnvVarSource vault = injectingSource("vault", 10, Map.of("MY_VAR", "from-vault"));
		loaderWith(vault).postProcessEnvironment(env, new SpringApplication());

		assertEquals("from-os", env.getProperty("MY_VAR"),
				"S.O. deve sobrescrever Vault na cadeia de PropertySources");
	}

	// -------------------------------------------------------------------------
	// múltiplas fontes e ordenação
	// -------------------------------------------------------------------------

	@Test
	void deveRegistrarMultiplasFontesNaOrdemDeGetOrder() {
		EnvVarSource src1 = noopSource("fonte-a", 20, Set.of("VAR_A"));
		EnvVarSource src2 = noopSource("fonte-b", 5,  Set.of("VAR_B"));
		EnvVarSource src3 = noopSource("fonte-c", 10, Set.of("VAR_C"));

		loaderWith(src1, src2, src3).postProcessEnvironment(emptyEnv(), new SpringApplication());

		List<EnvVarSource> active = EnvVarSourceRegistry.getActiveSources();
		assertEquals(3, active.size());
		assertEquals("fonte-b", active.get(0).name());
		assertEquals("fonte-c", active.get(1).name());
		assertEquals("fonte-a", active.get(2).name());
	}

	@Test
	void deveGetAllVarNamesRetornarUniaoDasVariaveis() {
		EnvVarSource s1 = noopSource("s1", 0,  Set.of("VAR_A", "SHARED"));
		EnvVarSource s2 = noopSource("s2", 10, Set.of("VAR_B", "SHARED"));

		loaderWith(s1, s2).postProcessEnvironment(emptyEnv(), new SpringApplication());

		Set<String> all = EnvVarSourceRegistry.getAllVarNames();
		assertTrue(all.containsAll(Set.of("VAR_A", "VAR_B", "SHARED")));
	}

	@Test
	void deveAtribuicaoFirstWinsParaVarPresenteEmMultiplasFontes() {
		EnvVarSource s1 = noopSource("system-env", 0,  Set.of("SHARED_VAR"));
		EnvVarSource s2 = noopSource("vault",      10, Set.of("SHARED_VAR"));

		loaderWith(s1, s2).postProcessEnvironment(emptyEnv(), new SpringApplication());

		Map<String, String> attributions = EnvVarSourceRegistry.getVarAttributions();
		assertEquals("system-env", attributions.get("SHARED_VAR"),
				"Fonte de menor order (maior prioridade) deve vencer na atribuição");
	}

	// -------------------------------------------------------------------------
	// on-failure
	// -------------------------------------------------------------------------

	@Test
	void deveOnFailureFallPadraoLancarEnvVarSourceLoadException() {
		EnvVarSource failing = failingSource("vault");

		assertThrows(EnvVarSourceLoadException.class,
				() -> loaderWith(failing).postProcessEnvironment(emptyEnv(), new SpringApplication()));
	}

	@Test
	void deveOnFailureWarnRegistrarFonteEContinuar() {
		EnvVarSource failing = failingSource("vault");
		StandardEnvironment env = envWithProp("env.governance.sources.vault.on-failure", "warn");

		assertDoesNotThrow(() -> loaderWith(failing).postProcessEnvironment(env, new SpringApplication()));

		List<EnvVarSource> active = EnvVarSourceRegistry.getActiveSources();
		assertEquals(1, active.size());
		assertEquals("vault", active.get(0).name());
	}

	@Test
	void deveOnFailureSkipNaoRegistrarFonte() {
		EnvVarSource failing = failingSource("vault");
		StandardEnvironment env = envWithProp("env.governance.sources.vault.on-failure", "skip");

		assertDoesNotThrow(() -> loaderWith(failing).postProcessEnvironment(env, new SpringApplication()));

		assertTrue(EnvVarSourceRegistry.getActiveSources().isEmpty());
	}

	// -------------------------------------------------------------------------
	// kill-switch
	// -------------------------------------------------------------------------

	@Test
	void deveIgnorarTudoQuandoGovernanceEstaDesabilitado() {
		EnvVarSource source = noopSource("system-env", 0, Set.of("HOME"));
		StandardEnvironment env = envWithProp("env.governance.enabled", "false");

		loaderWith(source).postProcessEnvironment(env, new SpringApplication());

		assertTrue(EnvVarSourceRegistry.getActiveSources().isEmpty());
	}
}
