package com.example.envgovernance.autoconfigure;

import java.util.List;
import java.util.Set;

/**
 * Classificador de variáveis de ambiente "de infraestrutura": vars bem conhecidas do
 * S.O., runtime (JVM/Maven/Gradle), orquestrador (Kubernetes) e runners de CI.
 * <p>
 * Essas variáveis quase nunca casam com propriedades da aplicação e, em testes/containers,
 * inundam a seção {@code [UNUSED]} do relatório. Este classificador permite separá-las do
 * ruído acionável para que sejam colapsadas em uma única linha ({@code [SYSTEM]}).
 * <p>
 * A comparação é case-insensitive via {@link EnvVarNormalizer} (cobre variações do Windows
 * como {@code Path} → {@code PATH}, {@code ProgramFiles} → {@code PROGRAMFILES}).
 *
 * @author Sartre Brasil
 * @since 2.1
 * @see EnvVarUsageReporter
 */
final class WellKnownSystemEnvVars {

	private WellKnownSystemEnvVars() {}

	/** Nomes exatos (já normalizados) de vars de S.O./runtime bem conhecidas. */
	private static final Set<String> KNOWN_NAMES = Set.of(
			// --- POSIX / shell ---
			"PATH", "HOME", "USER", "LOGNAME", "SHELL", "PWD", "OLDPWD", "SHLVL",
			"TERM", "COLORTERM", "LANG", "LANGUAGE", "TZ", "MAIL", "EDITOR", "PAGER",
			"DISPLAY", "HOSTNAME", "HOSTTYPE", "MACHTYPE", "OSTYPE", "TMPDIR", "_",
			// --- Windows ---
			"COMSPEC", "PATHEXT", "SYSTEMROOT", "SYSTEMDRIVE", "WINDIR", "OS",
			"HOMEDRIVE", "HOMEPATH", "USERPROFILE", "USERNAME", "USERDOMAIN",
			"APPDATA", "LOCALAPPDATA", "PROGRAMDATA", "PROGRAMFILES",
			"PROGRAMW6432", "COMMONPROGRAMFILES", "PUBLIC", "ALLUSERSPROFILE",
			"COMPUTERNAME", "NUMBER_OF_PROCESSORS", "SESSIONNAME", "TEMP", "TMP",
			// --- JVM / build tools ---
			"JAVA_HOME", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH",
			"M2_HOME", "MAVEN_HOME", "MAVEN_OPTS", "MAVEN_CONFIG", "MAVEN_PROJECTBASEDIR",
			"MAVEN_CMD_LINE_ARGS", "GRADLE_HOME", "GRADLE_OPTS", "GRADLE_USER_HOME",
			"GROOVY_HOME",
			// --- CI genéricos ---
			"CI", "GITLAB_CI"
	);

	/**
	 * Prefixos (já normalizados) de namespaces de infraestrutura. Uma var é considerada
	 * de S.O./runner se seu nome normalizado começa com qualquer um destes.
	 */
	private static final List<String> KNOWN_PREFIXES = List.of(
			"LC_", "XDG_", "GTK_", "DBUS_", "SSH_", "GPG_", "SYSTEMD_", "SNAP_",
			"PROCESSOR_", "KUBERNETES_", "GITHUB_", "GITLAB_", "RUNNER_", "ACTIONS_",
			"BUILDKITE_", "CI_", "SUREFIRE_", "FAILSAFE_", "PROGRAMFILES", "COMMONPROGRAMFILES"
	);

	/**
	 * Indica se a variável é de infraestrutura (S.O./runtime/CI), considerando a lista
	 * curada embutida somada aos padrões fornecidos pelo usuário.
	 *
	 * @param varName      nome bruto da variável (case/formatação originais)
	 * @param userPatterns padrões adicionais; {@code FOO*} = prefixo, {@code FOO} = nome exato
	 */
	static boolean isSystemVar(String varName, Set<String> userPatterns) {
		String norm = EnvVarNormalizer.normalize(varName);

		if (KNOWN_NAMES.contains(norm)) {
			return true;
		}
		for (String prefix : KNOWN_PREFIXES) {
			if (norm.startsWith(prefix)) {
				return true;
			}
		}
		if (userPatterns != null) {
			for (String pattern : userPatterns) {
				if (matchesUserPattern(norm, pattern)) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean matchesUserPattern(String normalizedName, String pattern) {
		String normPattern = EnvVarNormalizer.normalize(pattern);
		if (normPattern.endsWith("*")) {
			return normalizedName.startsWith(normPattern.substring(0, normPattern.length() - 1));
		}
		return normalizedName.equals(normPattern);
	}
}
