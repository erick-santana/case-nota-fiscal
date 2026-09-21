# SPEC-04 — Segurança e validação de entrada

| | |
|---|---|
| **Depende de** | SPEC-01 (stack), SPEC-03 (adaptador web e `ApiExceptionHandler` existem) |
| **Paralela a** | [SPEC-05](./SPEC-05-observabilidade-e-configuracao.md) — camadas diferentes, sem colisão |
| **Habilita** | SPEC-06 (matriz de promoção coerente com a postura de segurança) |
| **Decisões aplicáveis** | D-04; interage com D-03 e D-05 |
| **Altera comportamento observável?** | Sim, dentro do contrato: payloads hoje aceitos em silêncio passam a `400`; endpoint passa a exigir JWT |
| **Constituição** | [P2](../CONSTITUICAO.md#p2--o-contrato-json-é-imutável), [P5](../CONSTITUICAO.md#p5--um-teste-que-muda-junto-com-o-código-que-vigia-não-prova-nada) |

## Objetivo

Exigir autenticação no endpoint e validar o payload declarativamente, reduzindo a superfície de ataque e rejeitando dados malformados com erro claro — **sem alterar o contrato de entrada** (P2).

## Contexto verificado

`GeradorNFController` declara apenas `@RestController`, `@RequestMapping` e `@PostMapping`. Não há anotação de segurança, não há `@Valid` no `@RequestBody Pedido`, e não existia nenhum `@ControllerAdvice` em `src/main/java` antes de SPEC-03. `pom.xml` não declara `spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server` nem `spring-boot-starter-validation`. Nenhuma classe de `model/` tem anotação de Bean Validation.

Consequências concretas — não hipotéticas:

- **`500` por ausência de dado.** `destinatario.getEnderecos().stream()` (`GeradorNotaFiscalServiceImpl.java:90`) lança `NullPointerException` se `enderecos` for nulo. `destinatario` nulo já quebra antes, em `getTipoPessoa()` (linha 19). O cliente recebe um 500 opaco.
- **Resposta silenciosamente incorreta.** Se nenhum `Endereco` tiver finalidade `ENTREGA`/`COBRANCA_ENTREGA`, `regiao` fica nula, nenhum ramo de frete casa e `valorFreteComPercentual` permanece `0` — nota emitida com frete zerado, sem erro.
- **Faixa de alíquota escolhida por um campo não conferido.** `valor_total_itens` chega do cliente e dirige toda a escolha de alíquota, sem nenhuma validação contra os itens enviados.
- **Endpoint aberto.** Qualquer chamador que alcance a aplicação é atendido.

## Requisitos

**REQ-4.1 — Autenticação obrigatória, fail-closed.**
O endpoint não aceita requisição sem JWT válido. A cadeia protegida é o **default sem `@Profile`**; um perfil de ambiente novo herda a postura protegida por omissão.
*Verificação:* teste sob um perfil não previsto (ex.: `staging`) retorna `401` sem token.

**REQ-4.2 — A cadeia real é exercitada pelos testes.**
Os testes de segurança ativam explicitamente a cadeia JWT, não a permissiva de `local`/`test`.

**REQ-4.3 — `issuer-uri` externalizado.**
Vem de variável de ambiente; nenhum endpoint de IdP hardcoded.

**REQ-4.4 — Bean Validation em `model/`, sem tocar no contrato.**
As cinco classes recebem as constraints da tabela de desenho. Nenhum `@JsonProperty` é adicionado, removido ou renomeado (P2).
*Verificação:* teste de contrato de SPEC-02 passa **sem edição**.

**REQ-4.5 — Limite de tamanho de payload.**
`@Size(max = 500)` em `Pedido.itens`.

**REQ-4.6 — D-04 implementada.**
Consistência entre `valor_total_itens` e `Σ(valor_unitario × quantidade)`, com tolerância de arredondamento, rejeitando com `400`.

**REQ-4.7 — Erros de validação respondem `400` de fato.**
`@Valid @RequestBody` no controller e `ApiExceptionHandler` **estendido** (não duplicado), devolvendo `ResponseEntity` com `400`, corpo estruturado e sem stack trace.

**REQ-4.8 — Nenhuma resposta de erro expõe stack trace nem o valor recebido.**
Apenas o nome do campo e a regra violada — para não devolver dado pessoal em resposta de erro.

**REQ-4.9 — Regressão do NPE coberta.**
`destinatario.enderecos` ausente → `400`, não mais `500`.

**REQ-4.10 — Payloads de exemplo continuam aceitos.**
`teste-pf.json` e `teste-pj-simples.json` → `200`.

## Desenho

### Autenticação: validar o JWT também na aplicação

[RFC-0001](../../rfc/RFC-0001-arquitetura-produtiva-aws.md) já define autenticação na borda — API Gateway com autorizer JWT via Cognito/OIDC. A pergunta que esta spec decide é se a aplicação, atrás dessa borda, também valida o token.

**Decisão: sim.** Segmentação de rede não é autorização. Um VPC Link mal configurado, um *security group* com drift de IaC, uma chamada de outro serviço dentro da mesma VPC, ou um ambiente de teste apontado para o endpoint errado bastam para que a aplicação seja alcançada sem passar pelo autorizer. O custo é uma dependência e poucas linhas; o risco evitado é "sem nenhuma proteção".

#### Fail-closed, não por lista de perfis

Escopar a cadeia JWT por lista de perfis (`@Profile({"dev","prod"})`) é frágil: **qualquer perfil fora da lista — `staging`, que a matriz de promoção de SPEC-06 pressupõe, ou um `qa` futuro — não registraria nenhuma `SecurityFilterChain` do projeto**, caindo na auto-configuração default do Spring Boot. A postura de segurança passaria a depender de alguém lembrar de acrescentar o nome do perfil em duas listas.

Por isso: **a cadeia protegida é o default, sem `@Profile`.** A permissiva é a exceção explícita, anotada e com precedência declarada.

```java
// adapter/in/web/security/SecurityConfig.java
@Configuration
public class SecurityConfig {

    /** Default de toda a aplicação. Um perfil novo herda esta cadeia, protegida. */
    @Bean
    SecurityFilterChain jwtSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .csrf(AbstractHttpConfigurer::disable)   // API stateless, sem cookie de sessão
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    /** Actuator, em porta de management separada (SPEC-05). O controle real é de rede. */
    @Bean
    @Order(1)
    SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }

    /** Exceção deliberada: desenvolvimento e teste, sem Cognito real (ver SPEC-05). */
    @Bean
    @Profile({"local", "test"})
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain cadeiaPermissivaLocal(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }
}
```

`oauth2.jwt(Customizer.withDefaults())`, não `oauth2.jwt()` — a sobrecarga sem argumento foi deprecada no Spring Security 6.1 e removida nas versões alinhadas ao Spring Boot 4, que é a stack de SPEC-01.

O `issuer-uri` vem de variável de ambiente, nunca hardcoded (SPEC-05):

```properties
spring.security.oauth2.resourceserver.jwt.issuer-uri=${OIDC_ISSUER_URI}
```

O Actuator roda em **porta de management separada** (SPEC-05), com chain próprio via `EndpointRequest.toAnyEndpoint()`. Não é uma exceção de rota na API: como a porta é outra, quem alcança o Actuator é decidido por rede — *security group* do ALB e do scraper em produção, rede do compose localmente. Isso resolve quem pode raspar `/actuator/prometheus` sem furar a cadeia do endpoint de negócio.

#### Testar a cadeia real, não a permissiva

Se a suíte roda sob o perfil `test` e `test` usa a cadeia permissiva, **nenhum teste exercita a proteção** e REQ-4.1 fica sem evidência. Os testes de segurança ativam explicitamente a cadeia real:

```java
@SpringBootTest(webEnvironment = RANDOM_PORT)
@ActiveProfiles("dev")                       // cadeia JWT, não a permissiva
@AutoConfigureMockMvc
class ProtecaoDoEndpointTest {

    @MockitoBean JwtDecoder jwtDecoder;      // @MockBean foi removido no Spring Boot 4
    ...
}
```

`@MockitoBean`, não `@MockBean` — coerente com a stack de SPEC-01. Alternativa igualmente válida: `spring-security-test` com o post-processor `jwt()`, que dispensa mockar o decoder.

### Validação de payload

Bean Validation atua sobre o **valor** de um campo já desserializado. Não acrescenta, remove ou renomeia chave JSON alguma: o contrato de entrada continua idêntico (P2). O que muda é que valores hoje aceitos em silêncio passam a ser recusados com `400`.

| Classe | Campos | Constraint |
|---|---|---|
| `Pedido` | `idPedido` | `@NotNull @Positive` |
| | `data` | `@NotNull` |
| | `valorTotalItens`, `valorFrete` | `@NotNull @PositiveOrZero` |
| | `itens` | `@NotEmpty @Valid @Size(max = 500)` |
| | `destinatario` | `@NotNull @Valid` |
| `Item` | `idItem`, `descricao` | `@NotBlank` |
| | `valorUnitario` | `@NotNull @PositiveOrZero` |
| | `quantidade` | `@NotNull @Positive` |
| `Destinatario` | `nome` | `@NotBlank` |
| | `tipoPessoa` | `@NotNull` |
| | `regimeTributacao` | condicional (abaixo) |
| | `documentos`, `enderecos` | `@NotEmpty @Valid` |
| `Documento` | `numero` | `@NotBlank` |
| | `tipo` | `@NotNull` |
| `Endereco` | `cep`, `logradouro`, `numero`, `estado` | `@NotBlank` |
| | `complemento` | sem constraint — legitimamente opcional |
| | `finalidade`, `regiao` | `@NotNull` |

**`@PositiveOrZero` em `valorUnitario`**, não `@Positive`: um item de cortesia com valor zero é um caso de negócio legítimo e rejeitá-lo seria uma regra inventada por esta spec.

**`@Size(max = 500)` em `itens`** fecha um vetor de DoS trivial: hoje um `Pedido` com centenas de milhares de itens é desserializado, percorrido no cálculo e devolvido inteiro na resposta. O rate limiting da borda (RFC-0001) não protege contra uma única requisição gigante, e a mesma lógica de defesa em profundidade que justifica validar o JWT localmente se aplica aqui.

#### Troca de primitivos por wrappers

`idPedido` (`int`), `valorTotalItens`/`valorFrete`/`valorUnitario` (`double`) e `quantidade` (`int`) passam a `Integer`/`Double`. É necessário: um primitivo tem default (`0`/`0.0`) indistinguível de "campo ausente no JSON", o que torna `@NotNull` inócuo. O JSON externo não muda — ambos mapeiam para o mesmo `@JsonProperty` e o mesmo tipo JSON.

Ponto de atenção: **`400` não converte implicitamente para `Double`**. Java não faz boxing seguido de widening, então qualquer chamada como `pedido.setValorTotalItens(400)` com literal `int` deixa de compilar. As fixtures de SPEC-02 usam literais `double` (`400d`, `1500.0`) exatamente para que esta spec não obrigue a editar a suíte de caracterização (P5).

#### Regra condicional de regime

`regime_tributacao` só é obrigatório para `JURIDICA` — depende de outro campo, então vai como constraint de classe. Precisa de `@JsonIgnore`: sem ele, o getter sintético `isRegimeTributacaoInformadoParaJuridica()` seria serializado como campo extra na resposta, já que `Destinatario` é ecoado em `NotaFiscal.destinatario`.

```java
@AssertTrue(message = "regime_tributacao é obrigatório quando tipo_pessoa é JURIDICA")
@JsonIgnore
public boolean isRegimeTributacaoInformadoParaJuridica() {
    return tipoPessoa != TipoPessoa.JURIDICA || regimeTributacao != null;
}
```

Isso intercepta com `400` o caso de regime ausente. `RegimeTributacaoPJ.OUTROS` — informado, porém sem regra — passa pela validação e é recusado com `422` em [SPEC-03](./SPEC-03-nucleo-alvo.md#falta-de-policy-é-erro-não-alíquota-zero-d-03) (D-03). São dois erros distintos para duas situações distintas.

#### D-04 — consistência de `valor_total_itens`

`valor_total_itens` seleciona a faixa de alíquota e hoje é aceito sem conferência. Um cliente que informe um total menor que a soma real dos itens paga imposto de faixa inferior — é evasão fiscal por API, e explica diretamente a inconsistência de "valor total calculado" que o README relata.

```java
@AssertTrue(message = "valor_total_itens diverge da soma de valor_unitario × quantidade dos itens")
@JsonIgnore
public boolean isValorTotalItensConsistente() {
    if (valorTotalItens == null || itens == null || itens.isEmpty()) {
        return true;   // ausência é reportada pelas constraints de campo, não aqui
    }
    double soma = itens.stream()
            .filter(i -> i.getValorUnitario() != null && i.getQuantidade() != null)
            .mapToDouble(i -> i.getValorUnitario() * i.getQuantidade())
            .sum();
    return Math.abs(valorTotalItens - soma) <= 0.01;   // tolerância de arredondamento
}
```

Ambos os payloads de exemplo satisfazem a invariante (`50 × 2 = 100`; `730 × 8 = 5840`), o que indica que ela é real e não uma regra inventada aqui. É, ainda assim, a mudança mais assertiva desta entrega: payloads inconsistentes que antes eram aceitos passam a ser recusados. Está registrada como [D-04](../CONSTITUICAO.md#d-04--valor_total_itens-passa-a-ser-validado-contra-os-itens) para ser confirmada pelo dono do produto.

> A invariante confere `valor_total_itens` contra `Σ(valor_unitario × quantidade)`. O tributo por item, esse sim, continua ignorando `quantidade` — ver [D-05](../CONSTITUICAO.md#d-05--valortributoitem-continua-ignorando-quantidade). As duas coisas são independentes, e a assimetria entre elas é mais um indício de que D-05 merece revisão de negócio.

### Tratamento de erro

SPEC-03 criou `ApiExceptionHandler` com o handler de `422`. Esta spec **estende a mesma classe** — nenhuma classe nova, nenhum retrabalho (P1):

```java
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(RegimeTributacaoNaoSuportadoException.class)   // criado em SPEC-03
    public ResponseEntity<ErroResposta> tratarRegimeNaoSuportado(...) { /* 422 */ }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErroResposta> tratarCamposInvalidos(MethodArgumentNotValidException ex) {
        List<CampoInvalido> campos = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new CampoInvalido(e.getField(), e.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest().body(ErroResposta.de(BAD_REQUEST, "Payload inválido", campos));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErroResposta> tratarPayloadMalformado(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(
                ErroResposta.de(BAD_REQUEST, "Payload malformado",
                        "O corpo da requisição não pôde ser interpretado como um Pedido."));
    }
}
```

**`ResponseEntity`, não o objeto cru.** Devolver `ErroResposta` diretamente de um `@ExceptionHandler`, sem `@ResponseStatus`, produz **HTTP 200 com corpo de erro** — pior que o 500 atual, porque o cliente trata como sucesso. É um deslize fácil de cometer e difícil de notar em revisão.

`ErroResposta` não faz parte do contrato de `Pedido`/`NotaFiscal` e pode ser definido livremente.

## Plano de execução

- [x] Adicionar `spring-boot-starter-validation` e `spring-boot-starter-oauth2-resource-server`.
- [x] Anotar `Pedido`, `Item`, `Destinatario`, `Documento`, `Endereco` com Bean Validation, sem tocar em nenhum `@JsonProperty`. Desvio registrado: `Destinatario.enderecos` ficou `@NotNull` (não `@NotEmpty`) — ver nota abaixo.
- [x] Trocar os primitivos numéricos por wrappers nas cinco classes.
- [x] Adicionar `@Size(max = 500)` em `Pedido.itens`.
- [x] Implementar D-04: validação de consistência entre `valor_total_itens` e `Σ(valor_unitario × quantidade)`, com tolerância de arredondamento.
- [x] Constraint condicional: `regime_tributacao` obrigatório quando `tipo_pessoa == JURIDICA`, anotada com `@JsonIgnore` para não vazar na resposta.
- [x] `@Valid @RequestBody` no controller e `ApiExceptionHandler` estendido, devolvendo **`400` de fato** (`ResponseEntity`), com corpo estruturado e sem stack trace.
- [x] `SecurityFilterChain` com JWT como complemento explícito de `local`/`test` (`@Profile("!local & !test")`, não incondicional — ver nota abaixo); cadeia permissiva apenas sob `local`/`test`, com `@Order` explícito (*fail-closed*).
- [x] Chain dedicado do Actuator via `EndpointRequest.toAnyEndpoint()` — depende do `spring-boot-starter-actuator` que só SPEC-05 introduz; implementado em `SecurityConfig` por [SPEC-05](./SPEC-05-observabilidade-e-configuracao.md#actuator-em-porta-de-management-separada), não aqui (ver nota abaixo).
- [x] Escrever os testes de segurança contra a cadeia real (`@ActiveProfiles("dev")` + `@MockitoBean JwtDecoder`) e contra um perfil não previsto (`@ActiveProfiles("qa")`, sem propriedade alguma).

**Desvios em relação ao desenho literal desta spec, e por quê:**

1. **`Destinatario.enderecos` é `@NotNull`, não `@NotEmpty`.** A suíte de caracterização de SPEC-02 (`GerarNotaFiscalCaracterizacaoTest.deveAplicarMultiplicadorDeFretePorRegiao`, caso `regiao=null`) usa uma lista de endereços **vazia** — não nula — para representar "sem endereço de entrega", e espera `200` com frete `0`. `@NotEmpty` rejeitaria esse caso legítimo com `400`, violando P5 (a suíte não pode ser editada). `@NotNull` sozinho já cobre a regressão de NPE de REQ-4.9.
2. **A cadeia JWT (`jwtSecurityFilterChain`) tem `@Profile("!local & !test")`, não é incondicional.** `WebSecurityConfiguration.setFilterChains` constrói todas as `SecurityFilterChain` no boot, elegível — uma cadeia incondicional exigiria um `JwtDecoder` resolvível mesmo sob `local`/`test`, que não têm IdP nenhum. O complemento explícito de `local`/`test` preserva a mesma garantia de *fail-closed* (qualquer perfil novo cai na cadeia protegida) sem essa dependência.
3. **`SecurityConfig` define seu próprio bean `JwtDecoder`** (em vez de depender só de `spring.security.oauth2.resourceserver.jwt.issuer-uri` na auto-configuração do Spring Boot, que backs off diante de um bean explícito). A auto-configuração resolve a property — e faz descoberta OIDC de rede — na construção do bean; sem `OIDC_ISSUER_URI`, isso impediria o boot em qualquer perfil não previsto, o que contradiz REQ-4.1 (o perfil deve subir e responder `401`, não falhar no boot). O bean próprio lê `OIDC_ISSUER_URI` diretamente (`@Value`, sem indireção por `application-{perfil}.properties`) e devolve um decoder que rejeita todo token quando a variável não está definida.
4. **Chain do Actuator não incluída.** O desenho desta spec mostra um `actuatorSecurityFilterChain` via `EndpointRequest.toAnyEndpoint()`, mas essa classe vem de `spring-boot-starter-actuator`, dependência que só SPEC-05 adiciona (não consta no plano de execução desta spec). Adicioná-la agora seria escopo de SPEC-05 antecipado sem necessidade — hoje não há endpoint de Actuator para proteger.

## Verificação

1. Sem header `Authorization`, sob perfil com a cadeia real → `401`.
2. JWT inválido/expirado → `401`.
3. JWT válido + payload válido → `200`, resposta idêntica ao comportamento de SPEC-03.
4. Perfil não previsto (ex.: `staging`) → cadeia protegida ativa; `401` sem token. **Teste da postura fail-closed**, não só da configuração nominal.
5. Unitários de `Validator` por campo da tabela: ausente/nulo/vazio/negativo produz a violação esperada.
6. `itens` acima do limite de `@Size` → `400`.
7. D-04: `valor_total_itens` divergente da soma → `400`; divergência dentro da tolerância → `200`.
8. `JURIDICA` sem `regime_tributacao` → `400` (distinto do `422` de `OUTROS`, coberto em SPEC-03).
9. JSON sintaticamente inválido → `400`.
10. `destinatario.enderecos` ausente → `400`, não mais `500` — regressão do NPE.
11. Os dois payloads de exemplo continuam retornando `200`.
12. Resposta não ganhou campo algum: `isRegimeTributacaoInformadoParaJuridica` e `isValorTotalItensConsistente` não aparecem no JSON — coberto pelo teste de contrato de SPEC-02, que passa **sem edição**.

## Fora de escopo

- Rate limiting — delegado à borda (API Gateway, RFC-0001). O que esta spec cobre da mesma família é o limite de tamanho de uma requisição única (REQ-4.5), que a borda não protege.
- Autorização por escopo/role: o endpoint é único e não há papéis definidos no desafio. A cadeia autentica; autorizar por escopo entra quando existirem perfis de consumidor.
- Provisionar Cognito ou qualquer IdP real — P4.
- Mascaramento de dado pessoal em log — SPEC-05.
