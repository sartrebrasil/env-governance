# env-governance

Biblioteca de governança de variáveis de ambiente para aplicações Java. Detecta gaps de configuração no startup — variáveis obrigatórias ausentes, placeholders sem valor, sobrescritas ativas do SO — e suporta fontes externas de segredos como o HashiCorp Vault.

Funciona em dois contextos:
- **Spring Boot 3.x** — integração automática via `EnvironmentPostProcessor` e Actuator.
- **Qualquer aplicação Java** — API standalone sem dependências de framework.

[![Spring Boot 3.x](https://img.shields.io/badge/Spring%20Boot-3.x-6DB33F)](https://spring.io/projects/spring-boot)
[![Java 21](https://img.shields.io/badge/Java-21-ED8B00)](https://openjdk.org/projects/jdk/21/)

---

## Índice

- [O problema que resolve](#o-problema-que-resolve)
- [Módulos: qual dependência usar](#módulos-qual-dependência-usar)
- [Instalação e uso básico](#instalação-e-uso-básico)
  - [Apps Spring Boot](#apps-spring-boot)
  - [Apps Spring Boot com Vault](#apps-spring-boot-com-vault)
  - [Apps não-Spring](#apps-não-spring)
  - [Apps não-Spring com Vault](#apps-não-spring-com-vault)
- [Como funciona](#como-funciona)
  - [Spring Boot — pipeline de inicialização](#spring-boot--pipeline-de-inicialização)
  - [Apps não-Spring — GovernanceContext](#apps-não-spring--governancecontext)
- [Configuração](#configuração)
  - [Propriedades Spring Boot](#propriedades-spring-boot)
  - [Configuração do Vault](#configuração-do-vault)
- [Interpretando os logs](#interpretando-os-logs)
- [Endpoint Actuator](#endpoint-actuator)
- [Implementando uma fonte customizada (SPI)](#implementando-uma-fonte-customizada-spi)
- [Referência de gaps](#referência-de-gaps)

---

## O problema que resolve

Em containers e ambientes cloud, propriedades do Spring Boot podem ser sobrescritas por variáveis de ambiente a qualquer momento. Descobrir que uma variável está faltando, errada ou sendo ignorada costuma acontecer em produção, quando o custo é alto.

O **env-governance** roda no startup, cruza as propriedades declaradas com o ambiente do SO/container — e com fontes externas de segredos — e reporta exatamente o que está mal configurado, antes de qualquer bean ser criado.

```
===== ENV GOVERNANCE: VARIÁVEIS OBRIGATÓRIAS AUSENTES (2) =====
  DB_PASSWORD                                    → spring.datasource.password  [application.yml]
  API_KEY                                        → integration.api.key         [application.yml]
=================================================================
Defina as variáveis acima no ambiente antes de iniciar a aplicação.
```

---

## Módulos: qual dependência usar

O projeto é dividido em módulos com escopos bem definidos. Escolha conforme o seu contexto:

| Situação | Dependência recomendada |
|---|---|
| App Spring Boot, sem Vault | `env-governance-starter` |
| App Spring Boot, com Vault | `env-governance-vault-starter` |
| App não-Spring (Quarkus, Micronaut, Java puro, etc.) | `env-governance-java` |
| App não-Spring, com Vault | `env-governance-java` + `env-governance-vault` |
| Implementar fonte customizada de segredos | `env-governance-java` |

### Descrição de cada módulo

| Módulo | Descrição | Deps em runtime |
|---|---|---|
| `env-governance-java` | SPI `EnvVarSource`, `GovernanceContext` e `EnvFileReader`. Zero dependências externas — apenas JDK 21. | Nenhuma |
| `env-governance-core` | `EnvironmentPostProcessor`s Spring Boot que alimentam os registries estáticos no startup. | `env-governance-java`, `spring-boot` |
| `env-governance-autoconfigure` | Auto-configuração Spring Boot, reporter de logs e endpoint Actuator. | `env-governance-core`, `spring-boot` |
| `env-governance-starter` | Agregador: `env-governance-core` + `env-governance-autoconfigure`. | (transitivo) |
| `env-governance-vault` | `VaultEnvVarSource` — lê segredos do HashiCorp Vault. Zero dependências externas — apenas JDK 21 + `env-governance-java`. | `env-governance-java` |
| `env-governance-vault-starter` | Agregador: `env-governance-starter` + `env-governance-vault`. | (transitivo) |

> **Nota:** `env-governance-vault` não depende do Spring Boot. Ele funciona tanto em apps Spring Boot (carregado automaticamente via `ServiceLoader` pelo core) quanto em apps não-Spring (instanciado diretamente ou via `ServiceLoader`).

---

## Instalação e uso básico

### Apps Spring Boot

Adicione o starter ao `pom.xml`. Nenhuma configuração adicional é necessária — a auto-configuração ativa tudo automaticamente.

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>env-governance-starter</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

O diagnóstico aparece nos logs assim que a aplicação inicia. Para declarar variáveis obrigatórias, use placeholders sem default no `application.yml`:

```yaml
spring:
  datasource:
    url: ${DB_URL}              # obrigatória — startup falha se ausente
    password: ${DB_PASSWORD}    # obrigatória — startup falha se ausente
    username: ${DB_USER:app}    # opcional — usa "app" se ausente
```

O `RequiredEnvCheckEnvironmentPostProcessor` detecta as variáveis `DB_URL` e `DB_PASSWORD` como obrigatórias e aborta o startup com uma mensagem listando **todas** as variáveis faltantes de uma vez, antes de qualquer bean ser criado.

---

### Apps Spring Boot com Vault

Use o starter completo que inclui a integração com o HashiCorp Vault:

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>env-governance-vault-starter</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

Configure o Vault no `application.yml`:

```yaml
env:
  governance:
    sources:
      vault:
        address: "${VAULT_ADDR}"
        auth:
          method: token           # ou approle
          token: "${VAULT_TOKEN}"
        paths:
          - secret/myapp          # KV v2: env-governance insere /data/ automaticamente
          - secret/shared
```

Os segredos lidos do Vault ficam disponíveis como propriedades Spring e são verificados pelo `RequiredEnvCheckEnvironmentPostProcessor` — exatamente como variáveis do SO.

```yaml
# Referencia segredos que vêm do Vault:
spring:
  datasource:
    password: ${DB_PASSWORD}    # vem do Vault: secret/myapp → DB_PASSWORD
```

---

### Apps não-Spring

Para aplicações que não usam o ecossistema Spring (Quarkus, Micronaut sem compat. Spring, Java puro, etc.), use apenas o módulo java:

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>env-governance-java</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

Chame `GovernanceContext.builder()` no início do `main()`, antes de qualquer inicialização do framework:

```java
public static void main(String[] args) {
    GovernanceContext.builder()
        .require("DB_URL")
        .require("API_KEY")
        .build()
        .verify();  // lança MissingRequiredEnvironmentVariablesException se faltar algo

    // inicialização normal da aplicação
    startApp(args);
}
```

#### Carregando arquivos de configuração

Se sua aplicação usa arquivos `.env` ou `.properties` para configuração local:

```java
GovernanceContext.builder()
    .require("DB_URL")
    .require("API_KEY")
    .fromDotEnv(Path.of(".env"))              // KEY=VALUE, comentários com #
    .fromProperties(Path.of("app.properties")) // formato java.util.Properties
    .build()
    .verify();
```

Arquivos são ignorados silenciosamente se não existirem — seguro usar em todos os ambientes.

**Formato `.env` suportado:**

```dotenv
# Comentários são ignorados
DB_URL=jdbc:postgresql://localhost:5432/mydb
API_KEY="minha-chave-com-espacos"
APP_SECRET='outro-valor-entre-aspas'
```

#### Verificação sem lançar exceção

Para controlar o comportamento manualmente:

```java
GovernanceResult result = GovernanceContext.builder()
    .require("DB_URL")
    .require("API_KEY")
    .build()
    .check();  // não lança — retorna resultado

if (result.hasGaps()) {
    result.missingRequired().forEach(name ->
        System.err.println("Variável ausente: " + name));
    System.exit(1);
}
```

#### Ordem de prioridade

Quando a mesma variável existe em múltiplas fontes, a prioridade é (maior para menor):

1. Variáveis do SO (`System.getenv()`)
2. Fontes dinâmicas (`EnvVarSource`) na ordem de `getOrder()`
3. Arquivos (`.env`, `.properties`) na ordem de registro

---

### Apps não-Spring com Vault

Adicione `env-governance-vault` ao lado de `env-governance-java`:

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>env-governance-java</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>com.example</groupId>
    <artifactId>env-governance-vault</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

O `VaultEnvVarSource` é descoberto automaticamente via `ServiceLoader` quando presente no classpath — nenhuma configuração adicional de código é necessária. Configure via variáveis de ambiente:

```dotenv
VAULT_ADDR=https://vault.example.com
VAULT_AUTH_METHOD=token
VAULT_TOKEN=hvs.xxxxxxxx
VAULT_PATHS=secret/myapp,secret/shared
```

```java
public static void main(String[] args) {
    GovernanceContext.builder()
        .require("DB_URL")      // pode vir do Vault: secret/myapp → DB_URL
        .require("API_KEY")     // pode vir do Vault: secret/shared → API_KEY
        .build()
        .verify();

    startApp(args);
}
```

Para registrar o Vault explicitamente (desabilitando a descoberta automática via `ServiceLoader`):

```java
GovernanceContext.builder()
    .require("DB_URL")
    .withoutServiceLoaderDiscovery()  // controle total sobre fontes
    .addSource(new VaultEnvVarSource())
    .build()
    .verify();
```

---

## Como funciona

### Spring Boot — pipeline de inicialização

```
1. ConfigDataEnvironmentPostProcessor (Spring Boot, +10)
   └─ Carrega application*.yml/properties nas PropertySources

2. DeclaredVarsScanner (+15)
   └─ Para cada propriedade nas PropertySources do classpath:
       ├─ Registra var derivada pela CHAVE (relaxed binding):
       │     spring.datasource.password → SPRING_DATASOURCE_PASSWORD
       └─ Escaneia o VALOR em busca de ${PLACEHOLDER}:
             ${DB_PASSWORD}   → explicit=true, hasDefault=false (obrigatória)
             ${DB_USER:app}   → explicit=true, hasDefault=true  (opcional)

3. EnvVarSourceLoaderPostProcessor (+18)
   └─ Descobre EnvVarSource via java.util.ServiceLoader
   └─ Chama isAvailable() → load() para cada fonte (Vault, SO, etc.)
   └─ Injeta PropertySources após "systemEnvironment"

4. RequiredEnvCheckEnvironmentPostProcessor (+20)
   └─ Verifica vars com explicit=true AND hasDefault=false
   └─ Lança MissingRequiredEnvironmentVariablesException com TODAS as ausentes

5. UsageTrackingEnvironmentPostProcessor (LOWEST_PRECEDENCE)
   └─ Envolve PropertySources para rastrear chaves lidas em runtime

6. EnvVarUsageReporter (ApplicationReadyEvent)
   └─ Cruza DeclaredVarsRegistry × EnvVarSourceRegistry
       ├─ INFO  → gaps acionáveis
       └─ DEBUG → diagnóstico completo com atribuição por fonte
```

#### Relaxed Binding (chave)

Toda chave de propriedade é convertida para o nome de variável equivalente do Spring Boot:

| Chave da propriedade | Variável de ambiente |
|---|---|
| `spring.application.name` | `SPRING_APPLICATION_NAME` |
| `server.ssl.key-store` | `SERVER_SSL_KEY_STORE` |
| `spring.datasource.hikari.max-pool-size` | `SPRING_DATASOURCE_HIKARI_MAX_POOL_SIZE` |

#### Placeholder explícito (valor)

Se o valor da propriedade contém `${VAR_NAME}` ou `${VAR_NAME:default}`, o `VAR_NAME` é registrado com o nome literal — que **pode diferir** do nome derivado pela chave:

```yaml
bridge:
  poller:
    max-messages-per-poll: ${BRIDGE_MAX_MESSAGES_PER_POLL:100}
```

→ registra **dois** nomes distintos:
- `BRIDGE_POLLER_MAX_MESSAGES_PER_POLL` — derivado da chave (relaxed binding)
- `BRIDGE_MAX_MESSAGES_PER_POLL` — nome explícito do placeholder

---

### Apps não-Spring — GovernanceContext

```
1. GovernanceContext.builder().require("VAR").build()
   └─ Registra variáveis obrigatórias declaradas programaticamente

2. context.check() / context.verify()
   └─ Monta mapa de ambiente:
       a. Base: System.getenv()
       b. Fontes EnvVarSource (ServiceLoader ou explícitas), por getOrder():
          └─ load(Map<String,String>) → putIfAbsent (S.O. vence)
       c. Arquivos .env/.properties → putIfAbsent (menor prioridade)
   └─ Verifica vars declared via require() contra o mapa
   └─ verify() lança MissingRequiredEnvironmentVariablesException com lista completa
      check() retorna GovernanceResult sem lançar
```

---

## Configuração

### Propriedades Spring Boot

Adicione ao `application.yml` apenas o que precisar sobrescrever. Todos os defaults são razoáveis.

```yaml
env:
  governance:
    enabled: true              # false desliga toda a lib (útil em testes de integração)
    fail-on-missing: true      # false desabilita o early check de obrigatórias
    report-on-ready: true      # false suprime o relatório no ApplicationReadyEvent
    report-orphan-vars: true   # false suprime a seção [UNUSED]
    orphan-report-limit: 50    # limita vars não utilizadas exibidas para evitar spam
```

| Propriedade | Default | Descrição |
|---|---|---|
| `env.governance.enabled` | `true` | Desliga toda a lib. Útil em perfis de teste. |
| `env.governance.fail-on-missing` | `true` | Controla o early check de variáveis obrigatórias. Defina `false` em testes de integração que não precisam de todas as vars. |
| `env.governance.report-on-ready` | `true` | Controla se o relatório de diagnóstico é produzido no `ApplicationReadyEvent`. Desabilite se quiser apenas o endpoint Actuator. |
| `env.governance.report-orphan-vars` | `true` | Inclui a seção `[UNUSED]`. Desabilite em ambientes com muitas vars de infraestrutura irrelevantes. |
| `env.governance.orphan-report-limit` | `50` | Limita quantas vars não utilizadas são exibidas. |

**Desabilitando por perfil:**

```yaml
# application-test.yml
env:
  governance:
    enabled: false  # silencia tudo nos testes de integração
```

---

### Configuração do Vault

O Vault usa um padrão de dois níveis: chave Spring primeiro, variável de ambiente do SO como fallback. Isso permite configurar via `application.yml` (recomendado para Spring Boot) ou via variáveis de ambiente puras (recomendado para apps não-Spring e CI/CD).

#### Via `application.yml` (Spring Boot)

```yaml
env:
  governance:
    sources:
      vault:
        address: "${VAULT_ADDR}"                     # URL do servidor Vault
        auth:
          method: token                              # token ou approle
          token: "${VAULT_TOKEN}"                    # para auth por token
          # role-id: "${VAULT_ROLE_ID}"              # para AppRole
          # secret-id: "${VAULT_SECRET_ID}"          # para AppRole
        paths:
          - secret/myapp                             # KV v2: /data/ inserido automaticamente
          - secret/shared                            # primeiro caminho tem prioridade
        kv-version: 2                                # 1 ou 2 (default: 2)
        namespace: ""                                # Vault Enterprise: ex. "my-org/dev"
        timeout-seconds: 5                           # timeout da conexão HTTP
        tls:
          skip-verify: false                         # true APENAS em desenvolvimento local
```

#### Via variáveis de ambiente (não-Spring e CI/CD)

| Variável | Equivalente Spring | Descrição |
|---|---|---|
| `VAULT_ADDR` | `env.governance.sources.vault.address` | URL do servidor Vault |
| `VAULT_AUTH_METHOD` | `env.governance.sources.vault.auth.method` | `token` ou `approle` |
| `VAULT_TOKEN` | `env.governance.sources.vault.auth.token` | Token de autenticação |
| `VAULT_ROLE_ID` | `env.governance.sources.vault.auth.role-id` | AppRole: Role ID |
| `VAULT_SECRET_ID` | `env.governance.sources.vault.auth.secret-id` | AppRole: Secret ID |
| `VAULT_PATHS` | `env.governance.sources.vault.paths` | Caminhos separados por vírgula |
| `VAULT_KV_VERSION` | `env.governance.sources.vault.kv-version` | `1` ou `2` (default: `2`) |
| `VAULT_NAMESPACE` | `env.governance.sources.vault.namespace` | Vault Enterprise namespace |
| `VAULT_TIMEOUT_SECONDS` | `env.governance.sources.vault.timeout-seconds` | Timeout HTTP (default: `5`) |
| `VAULT_TLS_SKIP_VERIFY` | `env.governance.sources.vault.tls.skip-verify` | `true` só em dev local |

#### Autenticação AppRole

```yaml
# application.yml
env:
  governance:
    sources:
      vault:
        address: "${VAULT_ADDR}"
        auth:
          method: approle
          role-id: "${VAULT_ROLE_ID}"
          secret-id: "${VAULT_SECRET_ID}"
        paths:
          - secret/myapp
```

#### Múltiplos caminhos e prioridade

Quando o mesmo segredo existe em mais de um caminho, o **primeiro caminho** na lista vence:

```yaml
paths:
  - secret/myapp     # específico: tem prioridade
  - secret/shared    # genérico: segredos não presentes em myapp vêm daqui
```

#### Comportamento em caso de falha do Vault

Controle o que acontece se o Vault não responder:

```yaml
env:
  governance:
    sources:
      vault:
        on-failure: fail   # padrão — aborta o startup se o Vault não responder
        # on-failure: warn # loga aviso e continua (varNames vazio, sem segredos injetados)
        # on-failure: skip # loga aviso, não registra a fonte
```

---

## Interpretando os logs

### Nível INFO — gaps acionáveis

Aparece sempre que há algo a reportar. Usa `log.error` para casos críticos, `log.warn` para alertas.

**Com gaps:**

```
INFO  ===== ENV GOVERNANCE: GAPS DE CONFIGURAÇÃO =====

ERROR [REQUIRED] Variáveis obrigatórias ausentes no SO/container (1):
ERROR   DB_PASSWORD                                    →  spring.datasource.password  [application.yml]

WARN  [FALLBACK] Variáveis ausentes no SO/container, usando valor padrão (2):
WARN    BRIDGE_MAX_MESSAGES_PER_POLL                   →  bridge.poller.max-messages-per-poll  [application.yml]
WARN    REDIS_PORT                                     →  spring.data.redis.port  [application-redis.yml]

WARN  [NO VALUE] Propriedades sem valor no YAML e sem variável de ambiente (1):
WARN    SPRING_DATASOURCE_URL                          →  spring.datasource.url  [application.yml]

WARN  [UNUSED] Variáveis do SO/container não utilizadas pela aplicação (148):
WARN    COMPUTERNAME
WARN    HOME
WARN    JAVA_HOME
WARN    PATH
WARN    ...

INFO  =================================================
```

**Sem gaps:**

```
INFO  [ENV GOVERNANCE] Sem gaps de configuração detectados.
```

### Nível DEBUG — diagnóstico completo

Ative adicionando ao `application.yml`:

```yaml
logging:
  level:
    com.example.envgovernance: DEBUG
```

```
DEBUG ===== ENV GOVERNANCE: DIAGNÓSTICO COMPLETO (startup) =====

DEBUG [APPLICATION] Variáveis de ambiente potenciais (6 var(s)):
DEBUG   BRIDGE_MAX_MESSAGES_PER_POLL       [${} optional]  →  bridge.poller.max-messages-per-poll  [application.yml]
DEBUG   BRIDGE_POLLER_MAX_MESSAGES_PER_POLL [key]           →  bridge.poller.max-messages-per-poll  [application.yml]
DEBUG   DB_PASSWORD                        [${} required]  →  spring.datasource.password           [application.yml]
DEBUG   SERVER_PORT                        [${} optional]  →  server.port                          [application.yml]
DEBUG   SPRING_APPLICATION_NAME            [key]           →  spring.application.name              [application.yml]
DEBUG   SPRING_DATASOURCE_URL              [key/no-value]  →  spring.datasource.url                [application.yml]

DEBUG [ENVIRONMENT] Variáveis definidas (152 var(s) — system-env: 151, vault:secret/myapp: 1):
DEBUG   DB_PASSWORD        ← vault:secret/myapp
DEBUG   HOME               ← system-env
DEBUG   JAVA_HOME          ← system-env
DEBUG   SERVER_PORT        ← system-env
DEBUG   ...

DEBUG [ACTIVE] Substituições ativas: fontes sobrescrevendo propriedades (2 var(s)):
DEBUG   DB_PASSWORD              →  spring.datasource.password  [vault:secret/myapp]
DEBUG   SERVER_PORT              →  server.port                 [system-env]

DEBUG [UNUSED] Variáveis do ambiente não utilizadas pela aplicação (150 var(s.)):
DEBUG   HOME
DEBUG   JAVA_HOME
DEBUG   PATH
DEBUG   ...

DEBUG ==========================================================
```

### Marcadores na seção `[APPLICATION]`

| Marcador | Origem | Significado |
|---|---|---|
| `[key]` | Chave da propriedade | Var derivada por relaxed binding. O YAML tem valor — a env var é opcional. |
| `[key/no-value]` | Chave da propriedade | Var derivada, mas a propriedade está sem valor no YAML. |
| `[${} optional]` | Placeholder no valor | Var explícita com default: `${VAR:fallback}`. |
| `[${} required]` | Placeholder no valor | Var explícita sem default: `${VAR}`. Obrigatória. |

---

## Endpoint Actuator

Quando `spring-boot-actuator` está no classpath, o endpoint é registrado automaticamente. Basta expô-lo:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: env-governance
```

Acesse via `GET /actuator/env-governance`.

### Estrutura da resposta

```json
{
  "gaps": {
    "required": [
      {
        "name": "DB_PASSWORD",
        "propertyKey": "spring.datasource.password",
        "sourceFile": "application.yml"
      }
    ],
    "fallback": [
      {
        "name": "BRIDGE_MAX_MESSAGES_PER_POLL",
        "propertyKey": "bridge.poller.max-messages-per-poll",
        "sourceFile": "application.yml"
      }
    ],
    "noValue": []
  },
  "unused": ["HOME", "JAVA_HOME", "PATH"],
  "activeOverrides": [
    {
      "envVar": "SERVER_PORT",
      "propertyKey": "server.port",
      "sourceFile": "application.yml",
      "source": "system-env"
    }
  ],
  "applicationVars": [
    {
      "name": "DB_PASSWORD",
      "propertyKey": "spring.datasource.password",
      "sourceFile": "application.yml",
      "origin": "placeholder-required"
    }
  ],
  "sources": [
    { "name": "system-env",          "varCount": 152, "priority": 0  },
    { "name": "vault:secret/myapp",  "varCount": 5,   "priority": 10 }
  ]
}
```

| Campo | Conteúdo |
|---|---|
| `gaps.required` | Placeholders `${VAR}` sem default cujo `VAR` não está em nenhuma fonte. |
| `gaps.fallback` | Placeholders `${VAR:default}` cujo `VAR` não está em nenhuma fonte (usando fallback). |
| `gaps.noValue` | Propriedades sem valor no YAML e sem env var correspondente. |
| `unused` | Vars de todas as fontes sem correspondência em nenhuma propriedade da aplicação. |
| `activeOverrides` | Vars que estão ativamente sobrescrevendo propriedades, com atribuição da fonte. |
| `applicationVars` | Lista completa com `origin`: `key-derived`, `key-derived/no-value`, `placeholder-optional`, `placeholder-required`. |
| `sources` | Fontes ativas e contagem de variáveis, ordenadas por prioridade. |

---

## Implementando uma fonte customizada (SPI)

Para integrar com outras fontes de segredos (AWS Secrets Manager, Azure Key Vault, GCP Secret Manager, etc.), implemente a interface `EnvVarSource` do módulo `env-governance-java`:

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>env-governance-java</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

```java
package com.example.myapp.secrets;

import com.example.envgovernance.spi.EnvVarSource;

import java.util.Map;
import java.util.Set;

public final class AwsSecretsManagerSource implements EnvVarSource {

    private volatile Set<String> varNames = Set.of();

    @Override
    public String name() {
        return "aws-secrets-manager";
    }

    @Override
    public boolean isAvailable(Map<String, String> environment) {
        // Retorne false se a configuração mínima não estiver presente
        return environment.containsKey("AWS_SECRET_ARN")
                || environment.containsKey("aws.secrets.arn");
    }

    @Override
    public Map<String, String> load(Map<String, String> environment) {
        String arn = environment.getOrDefault("aws.secrets.arn",
                environment.get("AWS_SECRET_ARN"));

        // Busque os segredos do AWS...
        Map<String, String> secrets = fetchFromAws(arn);

        this.varNames = Set.copyOf(secrets.keySet());
        return secrets;
    }

    @Override
    public Set<String> getVarNames() {
        return varNames;
    }

    @Override
    public int getOrder() {
        return 20; // após Vault (10), antes do padrão de terceiros (100)
    }
}
```

Registre a implementação no arquivo de serviços do Java (`ServiceLoader`):

```
# src/main/resources/META-INF/services/com.example.envgovernance.spi.EnvVarSource
com.example.myapp.secrets.AwsSecretsManagerSource
```

A fonte é descoberta automaticamente:
- **Spring Boot** — pelo `EnvVarSourceLoaderPostProcessor` no startup.
- **Apps não-Spring** — pelo `GovernanceContext.builder().build()` (a menos que `.withoutServiceLoaderDiscovery()` seja usado).

### Contrato da interface

| Método | Quando é chamado | O que deve retornar |
|---|---|---|
| `isAvailable(env)` | Antes de `load()` | `false` se a configuração mínima não estiver presente — ignora a fonte silenciosamente |
| `load(env)` | Após `isAvailable()` retornar `true` | Mapa de variáveis a injetar; mapa vazio se a fonte não precisa injetar nada |
| `getVarNames()` | Após `load()` completar | Set de todos os nomes de variáveis gerenciados por esta fonte |
| `isSensitive(varName)` | Pelos reporters | `true` para suprimir o valor nos logs (default: detecta PASSWORD/SECRET/TOKEN/KEY/CREDENTIAL) |
| `getOrder()` | Para ordenação | Inteiro — menor valor = maior prioridade |

**Comportamento em caso de falha:** controlado pela propriedade `env.governance.sources.<nome>.on-failure` (Spring Boot) ou tratado pela aplicação (não-Spring, via `GovernanceResult.hasGaps()`).

---

## Referência de gaps

### `[REQUIRED]` — Crítico (`log.error`)

**O que é:** a propriedade usa `${VAR}` *sem valor de fallback* e `VAR` não está em nenhuma fonte (SO, Vault, etc.).

O Spring Boot tentará resolver o placeholder ao injetar a propriedade. Se nenhum bean ler essa propriedade no startup, a aplicação pode subir — mas falhará silenciosamente em runtime.

**O que fazer:** defina a variável no SO/container ou configure uma fonte de segredos que a forneça.

---

### `[FALLBACK]` — Atenção (`log.warn`)

**O que é:** a propriedade usa `${VAR:valor_padrão}` e `VAR` não está em nenhuma fonte. O valor padrão declarado no YAML está sendo utilizado.

Pode ser intencional (porta padrão em ambiente local) ou um esquecimento em produção.

**O que fazer:** confirme se o fallback é adequado para o ambiente atual.

---

### `[NO VALUE]` — Atenção (`log.warn`)

**O que é:** a propriedade existe no YAML mas está sem valor (null ou vazio), e não há variável de ambiente correspondente em nenhuma fonte.

A propriedade terá valor `null` em runtime. Componentes Spring que dependam dela podem falhar.

**O que fazer:** adicione um valor no YAML ou defina a variável correspondente no SO.

---

### `[UNUSED]` — Informativo (`log.warn`)

**O que é:** a variável está disponível em alguma fonte mas não corresponde a nenhuma propriedade conhecida da aplicação.

Possíveis causas: typo no nome, propriedade removida do YAML, variável de infraestrutura não relacionada.

**O que fazer:** verifique se o nome está correto ou se a variável pode ser removida do ambiente.

---

### Cenário: mesma propriedade, dois nomes de variável

O caso mais comum de confusão: um placeholder cujo nome difere do que o relaxed binding geraria para a chave. Ambos são registrados e qualquer um funciona para sobrescrever o valor.

```yaml
# application.yml
bridge:
  poller:
    max-messages-per-poll: ${BRIDGE_MAX_MESSAGES_PER_POLL:100}
```

```bash
# Opção 1 — sobrescreve via relaxed binding (nome derivado da chave)
BRIDGE_POLLER_MAX_MESSAGES_PER_POLL=200

# Opção 2 — sobrescreve via placeholder (nome explícito no YAML)
BRIDGE_MAX_MESSAGES_PER_POLL=200
```

> Quando `BRIDGE_POLLER_MAX_MESSAGES_PER_POLL` está definida no SO, ela sobrescreve a propriedade inteira via relaxed binding antes de o Spring avaliar o placeholder interno. Nesse caso, `[FALLBACK]` para `BRIDGE_MAX_MESSAGES_PER_POLL` no INFO é esperado e pode ser ignorado.
