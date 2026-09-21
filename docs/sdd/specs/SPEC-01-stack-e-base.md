# SPEC-01 — Stack e base do projeto

| | |
|---|---|
| **Depende de** | nada — é o passo 0 |
| **Habilita** | todas as demais specs |
| **Decisões aplicáveis** | D-01 (justificativa de adoção) |
| **Altera comportamento observável?** | Não |
| **Constituição** | [P1](../CONSTITUICAO.md#p1--nada-é-especificado-contra-um-estado-intermediário), [P2](../CONSTITUICAO.md#p2--o-contrato-json-é-imutável) |

## Objetivo

Colocar a aplicação em Java 21 e na versão estável mais recente do Spring Boot **antes** de qualquer refatoração, para que todo código desta entrega nasça na stack final e não precise ser revisitado.

## Por que este é o passo 0

Subir a stack antes de tudo é o que permite escrever cada peça uma única vez, na forma definitiva (P1): a paralelização de SPEC-03 já nasce com virtual threads, os testes de SPEC-02 e SPEC-04 já usam a API de mocks da versão-alvo (`@MockitoBean`), e o pipeline de SPEC-06 roda exatamente na stack final.

Além da conveniência, há um impedimento técnico que torna a ordem **obrigatória** — ver "Armadilha do Lombok".

## Contexto verificado

Estado atual (`pom.xml:7,16`): `spring-boot-starter-parent` `2.6.2`, `<java.version>11</java.version>`. Dependências: `spring-boot-starter`, `spring-boot-starter-web`, `lombok` (sem `<version>`, herdada do BOM), `spring-boot-starter-test`.

Exposição deste código às breaking changes de Boot 2→3→4, verificada arquivo a arquivo:

| Breaking change | Aplica-se? | Evidência |
|---|---|---|
| `javax.*` → `jakarta.*` | **Não** | `grep -rn "import javax\." src/main/java` → zero ocorrências |
| Remoção de `WebSecurityConfigurerAdapter` | **Não** | Sem `spring-boot-starter-security` no `pom.xml`; nenhum import de Spring Security |
| Remoção da auto-config de `RestTemplate` | **Não** | Nenhum cliente HTTP; as integrações são `Thread.sleep` |
| Remoção de `@MockBean`/`@SpyBean` | **Não** (hoje) | Os testes usam Mockito puro. **Relevante para SPEC-04**, que precisa de `@MockitoBean` |
| Remoção do JUnit 4 | **Não** | Ambos os testes já usam `org.junit.jupiter` |
| Jackson 2 → Jackson 3 | **Sem impacto** | Só se usa `@JsonProperty`, cujo módulo `jackson-annotations` mantém o pacote `com.fasterxml.jackson.annotation` |
| JPA / Undertow | **Não** | Ausentes do `pom.xml` |

Nenhuma classe própria do projeto é afetada. A exposição real está concentrada em dependências e configuração, não em código.

### Armadilha do Lombok

`lombok` não tem `<version>` no `pom.xml`, então a versão vem do BOM do parent. Sob `2.6.2` isso resolve **Lombok 1.18.22, que não compila sob JDK 21** (falha de annotation processing acessando internos de `com.sun.tools.javac`). Como todos os DTOs de `model/` dependem de Lombok, qualquer tentativa de usar JDK 21 **antes** desta spec quebra o build — inclusive num runner de CI, o que torna SPEC-01 pré-requisito de SPEC-06. Com o bump, o BOM resolve uma versão compatível e o problema desaparece; não é preciso pin manual.

## Requisitos

**REQ-1.1 — Parent e `java.version` na stack final.**
`pom.xml` aponta para `<java.version>21</java.version>` e para a patch mais recente da série estável mais recente do `spring-boot-starter-parent` compatível.
*Verificação:* `./mvnw clean verify` conclui sem erro imediatamente após o bump, antes de qualquer outra mudança.

**REQ-1.2 — Runtime Java 21 real, não só compilação.**
O jar gerado executa sob JVM 21 e a imagem OCI é produzida na mesma versão.
*Verificação:* `./mvnw clean package && java -jar target/*.jar`; `./mvnw spring-boot:build-image` e execução da imagem.

**REQ-1.3 — Os dois testes pré-existentes passam sem alteração de conteúdo.**
Só o pacote muda. Nenhuma asserção, nenhum valor, nenhum nome de método de teste é editado.
*Verificação:* `git diff` dos arquivos de teste mostra apenas a linha `package` e o rename de classe.

> **Divergência conhecida, pré-existente e independente deste bump:** `GeradorNotaFiscalServiceImplTest` só passa por completo quando cada método roda isolado (`./mvnw test -Dtest=...#método`, como o `CLAUDE.md` já documenta). Rodando a classe inteira — e portanto em `./mvnw clean verify` —, exatamente um dos dois métodos falha, sempre o que a JVM executa por último. Causa raiz: `GeradorNotaFiscalServiceImpl.gerarNotaFiscal` instancia `CalculadoraAliquotaProduto` com `new` em vez de usar o `@Mock`/`@InjectMocks` do teste, então cada teste bate na instância real, cujo campo `itemNotaFiscalList` é `static` e nunca é resetado entre execuções — a mesma leitura de vazamento entre requisições que o `CLAUDE.md` já descreve para produção. Confirmado isolando cada método (`./mvnw test -Dtest=...ImplTest#shouldGenerateNotaFiscalForTipoPessoaFisicaWithValorTotalItensLessThan500` passa sozinho; falha só quando a classe roda completa). Corrigir o campo `static` é mudança de lógica em `src/main/java` — fora do escopo desta spec (ver "Fora de escopo") — e será resolvido em SPEC-03. Por ora, REQ-1.3 é verificado rodando os dois métodos isoladamente; `./mvnw clean verify` fica com essa falha pontual conhecida até SPEC-03.

**REQ-1.4 — O pacote de testes espelha o da aplicação.**
`src/test/java/br/com/itau/calculadoratributos/` → `src/test/java/br/com/itau/geradornotafiscal/`, e `CalculadoratributosApplicationTests` → `GeradorNotaFiscalApplicationTests`. Feito aqui, e não em SPEC-02, para que a rede de testes já nasça no lugar definitivo (P1).
*Verificação:* `find src/test -path '*calculadoratributos*'` não retorna nada.

**REQ-1.5 — Contrato preservado.**
Nenhum `@JsonProperty` alterado; nenhum `import javax.*` introduzido.
*Verificação:* smoke test manual com `teste-pf.json` e `teste-pj-simples.json`, conferindo que os nomes de campo da resposta não mudaram.

**REQ-1.6 — Nenhuma dependência nova.**
`springdoc`, `validation`, `oauth2-resource-server`, `actuator` e `micrometer` entram nas specs que efetivamente os usam.

## Desenho

### Decisão de versão

Saltar `2.6.2` → **`4.1.1`**, pulando a série 3.x inteira.

- **Permanecer em 3.5.x** foi descartado: o suporte *open source* da série 3 terminou em 30/06/2026 (patch final `3.5.16`), então adotá-la já contraria o motivo de existir desta spec e deixa uma segunda migração pendente.
- **Migrar em dois passos (2→3→4)** é a recomendação padrão para bases com uso pesado de `javax.*`, JPA ou Spring Security — nada disso existe aqui. Dois PRs e duas rodadas de validação não se justificam com a tabela de exposição acima.
- **Só subir `java.version` mantendo Boot 2.6.2** é inválido: a série 2.x nunca declarou suporte a Java 21.

Requisitos da 4.1.x atendidos: Spring Framework 7.0.9+, Jakarta EE 11/Servlet 6.1+, Maven 3.6.3+ (o wrapper já pina `3.9.5`).

> Pesquisa datada de **20/09/2026**, via `docs.spring.io/spring-boot/system-requirements.html` e `endoflife.date/spring-boot`. O ciclo de release é contínuo — reconferir a patch mais recente imediatamente antes de aplicar.

### O que muda

```diff
 	<parent>
 		<groupId>org.springframework.boot</groupId>
 		<artifactId>spring-boot-starter-parent</artifactId>
-		<version>2.6.2</version>
+		<version>4.1.1</version>
 	</parent>
 	<properties>
-		<java.version>11</java.version>
+		<java.version>21</java.version>
 	</properties>
```

```
src/test/java/br/com/itau/calculadoratributos/   ->   src/test/java/br/com/itau/geradornotafiscal/
CalculadoratributosApplicationTests              ->   GeradorNotaFiscalApplicationTests
```

### Recursos de Java 21 adotados nesta entrega

O README pede que o uso de recursos novos seja justificado pelo benefício, não pela novidade. Avaliação, registrada aqui e válida para todas as specs:

| Recurso | Adotado? | Motivo |
|---|---|---|
| **Virtual threads** | **Sim**, em SPEC-03 | As quatro notificações são I/O bloqueante puro (`Thread.sleep` representando rede). É exatamente a carga em que virtual threads dispensam dimensionar pool. Ver [D-01](../CONSTITUICAO.md#d-01--as-notificações-são-paralelizadas-com-virtual-threads) |
| `record` | Não | Os DTOs precisam de `@NoArgsConstructor`/setters para o binding do Jackson e são o contrato congelado (P2); converter não traz ganho e arrisca o contrato |
| Pattern matching / `switch` de padrão | Pontual, em SPEC-03 | Onde substituir cadeias `if/else` por `switch` sobre enum melhorar a legibilidade das policies, sem forçar |
| `StructuredTaskScope` | Não | Ainda *preview*; `CompletableFuture.allOf` sobre um executor de virtual threads entrega o mesmo resultado com API estável |
| `spring.threads.virtual.enabled=true` (global) | Não | Ativaria virtual threads também no pool do Tomcat, mudança de perfil de execução sem necessidade; preferimos o executor dedicado e explícito das notificações |

## Plano de execução

- [x] Elevar `<java.version>` para `21` e `spring-boot-starter-parent` para a versão estável mais recente compatível (`4.1.1` na pesquisa de 20/09/2026 — reconferir em [spring.io/projects/spring-boot](https://spring.io/projects/spring-boot) antes de aplicar).
- [x] Rodar `./mvnw clean verify` imediatamente após o bump, antes de qualquer outra mudança, e resolver eventual pin pontual de dependência. Nenhum pin foi necessário; a única falha remanescente é a divergência pré-existente registrada em REQ-1.3.
- [x] Mover os testes de `br.com.itau.calculadoratributos` para `br.com.itau.geradornotafiscal`, espelhando `src/main/java`.
- [x] Renomear `CalculadoratributosApplicationTests` para `GeradorNotaFiscalApplicationTests`.
- [x] Validar `./mvnw spring-boot:run`, `./mvnw clean package` + `java -jar`, e smoke test manual com os dois payloads de exemplo.
- [x] Validar `./mvnw spring-boot:build-image` sob Java 21; fixar `BP_JVM_VERSION=21` no `pom.xml` **se e somente se** o builder Paketo não resolver a JVM 21 sozinho. O builder Paketo (`paketobuildpacks/builder-jammy-base`) resolveu Java 21.0.12 sozinho — nenhum pin necessário.

## Verificação

Esta spec não introduz regra de negócio — a verificação é de compilação, execução e regressão:

1. `./mvnw clean verify` conclui sem erro logo após o bump.
2. Os dois testes pré-existentes passam sem alteração de conteúdo (só de pacote).
3. `./mvnw spring-boot:run` sobe na porta 8080; smoke test com os dois payloads, conferindo nomes de campo da resposta.
4. `./mvnw clean package` + `java -jar target/*.jar` valida o runtime Java 21 real, não só o compilador.
5. `./mvnw spring-boot:build-image` produz imagem executável sob Java 21.
6. `find src/test -path '*calculadoratributos*'` vazio.

## Fora de escopo

- Qualquer refatoração de estrutura, nome ou lógica em `src/main/java` — é SPEC-03.
- Qualquer dependência nova (REQ-1.6).
- Ativar virtual threads globalmente (`spring.threads.virtual.enabled`); o executor dedicado é de SPEC-03.
