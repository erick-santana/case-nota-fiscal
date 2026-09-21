# SOLUÇÃO PROPOSTA — Desafio Técnico Nota Fiscal

> Este documento resume, num único lugar, **tudo que foi implementado**, **por quê**, e **como rodar o sistema e os testes localmente**. Ele é um resumo executivo: o detalhamento normativo de cada decisão vive em [`docs/sdd/`](docs/sdd/) (specs executáveis, uma por frente de trabalho), a arquitetura produtiva em [`docs/rfc/RFC-0001`](docs/rfc/RFC-0001-arquitetura-produtiva-aws.md) e [`docs/adr/ADR-0001`](docs/adr/ADR-0001-notificacoes-assincronas-saga-orquestrada.md), e o contexto de negócio em [`docs/VISAO-DE-NEGOCIO.md`](docs/VISAO-DE-NEGOCIO.md). Este arquivo referencia esses documentos em vez de duplicá-los, mas relata as decisões e justificativas centrais diretamente.

---

## 1. Como o trabalho foi conduzido

A entrega seguiu **Spec-Driven Development**: sete especificações executáveis (`docs/sdd/specs/SPEC-01` a `SPEC-07`), todas subordinadas a uma constituição (`docs/sdd/CONSTITUICAO.md`) com 5 princípios invioláveis (P1–P5) e 7 decisões transversais (D-01–D-07). A ideia central: nada é escrito contra um estado intermediário que já se sabe que será removido — cada peça nasce na forma final.

Ordem de execução (cada uma é portão para a seguinte):

```
SPEC-01 (stack) → SPEC-02 (rede de testes) → SPEC-03 (núcleo alvo) ┬→ SPEC-04 (segurança/validação) ┐
                                                                    └→ SPEC-05 (observabilidade)     ┴→ SPEC-06 (CI/arquitetura) → SPEC-07 (outbox)
```

Princípios que governam toda decisão abaixo:

- **P1** — nada é especificado contra um estado intermediário; todo design é escrito contra o estado final.
- **P2** — o contrato JSON de `Pedido`/`NotaFiscal` é imutável campo a campo.
- **P3** — as latências simuladas (`Thread.sleep`) são o cenário, não o problema; só a penalidade artificial de `+5s` é eliminada.
- **P4** — a aplicação roda localmente sem nenhuma credencial ou recurso AWS real.
- **P5** — um teste que muda junto com o código que vigia não prova nada (a suíte de caracterização não pode ser editada durante a reescrita, exceto uma única exceção documentada).

---

## 2. Problemas do enunciado e como foram resolvidos

### 2.1 Vazamento de estado entre requisições (bug funcional principal)

**Causa raiz:** `CalculadoraAliquotaProduto.itemNotaFiscalList` era um campo **`static`**. Cada `new CalculadoraAliquotaProduto()` por requisição não isolava nada — o campo pertencia à classe, não à instância — e a lista acumulava itens de todas as chamadas anteriores, vazando para `NotaFiscal.itens` na resposta HTTP. Também explicava a degradação de performance ao longo do tempo: a lista crescia, `itens.size()` ultrapassava 5 mesmo para pedidos pequenos, disparando a penalidade de +5s (ver 2.2) mesmo sem justificativa.

**Correção:** a calculadora foi reescrita como `domain/aliquota/CalculadoraTributoItem`, **stateless por construção** — nenhum campo de classe ou de instância, a lista de resultado é local ao método e só escapa como retorno. É deliberadamente correta tanto instanciada por chamada quanto como bean singleton (que é como está registrada) — a armadilha evitada foi tratar o bug como sendo o modificador `static`: remover só o `static` teria deixado um campo de instância que voltaria a vazar assim que o componente virasse singleton via injeção de dependência. **O problema é o estado, não o modificador.**

*Verificado por:* testes de isolamento em `GerarNotaFiscalCaracterizacaoTest` (idempotência, não-vazamento pedido-grande→pedido-pequeno, concorrência com pedidos distintos usando `CountDownLatch`) e cenário k6 B (execuções sucessivas sem tendência de crescimento de latência).

### 2.2 Penalidade de performance com >6 itens

**Causa raiz:** `EntregaIntegrationPort.criarAgendamentoEntrega` somava `Thread.sleep(5000)` quando `itens.size() > 5` (ou seja, a partir de **6** itens, não 7 como a redação do enunciado sugeria) — sem relação com o custo real de uma chamada de agendamento.

**Correção:** o `+5s` condicional simplesmente **não foi escrito** no adaptador novo (`EntregaIntegrationAdapter`) — não há "remoção", porque P1 não permite um passo intermediário em que ele exista no código novo. A latência real da integração (200ms) foi preservada.

*Verificado por:* cenário k6 A (1 item vs. 6 itens: `p95` de ~512ms vs. ~508ms — nenhum salto para a ordem de segundos que o código antigo produzia, ~5,35s).

### 2.3 Sequencialidade das quatro integrações downstream

**Causa raiz:** `EstoqueService`, `RegistroService`, `EntregaService` e `FinanceiroService` eram chamadas **síncronas e sequenciais**, cada uma instanciada com `new` (não eram beans Spring, não passavam por porta de saída, impossíveis de mockar sem reflexão). Soma sequencial: ~1480ms de latência artificial para qualquer pedido.

**Correção em duas etapas, ambas registradas nas decisões da constituição:**

- **D-01 (SPEC-03, depois superada):** as quatro chamadas foram extraídas para portas hexagonais (`EstoqueNotificacaoPort`, `RegistroNotificacaoPort`, `EntregaNotificacaoPort`+`EntregaIntegrationPort`, `FinanceiroNotificacaoPort`), implementadas por adaptadores injetados como beans, e disparadas **em paralelo** via `CompletableFuture` sobre um `Executor` de virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`). Reduziu o caminho feliz de ~1,3s (soma) para ~500ms (máximo das quatro) sem remover nenhum `Thread.sleep` (P3). Virtual threads foram escolhidas — e justificadas em `SPEC-01` como o único uso de recurso novo de Java 21 nesta entrega além de pattern matching pontual — porque as quatro chamadas são I/O bloqueante puro: é exatamente a carga em que dispensam dimensionamento de pool (um pool de plataforma pequeno enfileira sob concorrência, um grande desperdiça threads bloqueadas).
- **D-07 (SPEC-07, estado atual):** a paralelização resolvia latência, mas não o **acoplamento de disponibilidade** — a resposta HTTP ainda esperava a confirmação das quatro integrações; qualquer uma lenta ou fora do ar travava ou falhava a resposta inteira. A solução final (ver seção 4) substitui as quatro chamadas síncronas por uma única escrita transacional num **outbox DynamoDB** — a resposta HTTP não depende mais da disponibilidade de nenhuma das quatro áreas.

*Verificado por:* cenário k6 C (bloco de notificações ~504ms, não ~1480ms) e cenário k6 D (50 VUs concorrentes, 0% de falhas, latência estável).

### 2.4 Tratamento de erro nas integrações

Cinco pontos faziam `throw new RuntimeException(e)` sobre `InterruptedException`, descartando o status de interrupção da thread e sem dizer qual integração falhou nem para qual nota. Substituído por `IntegracaoDownstreamException(integracao, idNotaFiscal, causa)` com `Thread.currentThread().interrupt()` restaurado — importante porque, com virtual threads e um executor fechado no shutdown do contexto, um cancelamento cooperativo mascarado deixa de ser hipotético.

### 2.5 `RegimeTributacaoPJ.OUTROS` sem regra (D-03 — única mudança de comportamento observável deliberada)

Antes: nenhum ramo tratava esse valor e a nota saía com `itens: []`, **sem erro**. Correção: passou a responder **`422 Unprocessable Entity`** com erro explícito. Justificativa: num domínio fiscal, recusar é estritamente melhor que emitir uma nota fiscal vazia em silêncio. É a única mudança de comportamento observável autorizada pela constituição, e o teste de caracterização que documentava o comportamento antigo foi atualizado no mesmo commit que implementou a mudança — nunca como "ajuste para o teste passar".

### 2.6 Regras de alíquota concentradas e quase-duplicadas

Quatro ramos (`FISICA`, `SIMPLES_NACIONAL`, `LUCRO_REAL`, `LUCRO_PRESUMIDO`) viviam como `if/else` dentro do mesmo método de uma classe que sofria alterações frequentes. Extraídos para o padrão **Strategy**: uma interface `AliquotaPolicy` (`aplicavelPara` + `percentualPara`) e uma classe por regime, resolvidas por `CalculadoraAliquota` a partir de uma lista de policies injetada. Um quinto regime passa a ser uma classe nova registrada em `DomainConfig`, sem tocar nas quatro existentes nem no orquestrador — resolve diretamente o requisito de "facilitar futuras alterações e inclusão de novas regras" do README. Os limiares e percentuais numéricos foram copiados sem alteração de valor.

### 2.7 Duas divergências fiscais identificadas e **deliberadamente preservadas** (não corrigidas)

Duas inconsistências reais foram encontradas durante a análise, mas **não corrigidas nesta entrega** porque alterá-las mudaria o valor de toda nota emitida sem mandato de negócio — o README não as relata como bug, e a mudança de regra de cálculo fiscal é uma decisão de produto/compliance, não uma refatoração técnica:

- **D-05 — `valorTributoItem` ignora `quantidade`.** A fórmula é `valorUnitario × aliquota`, sem multiplicar pela quantidade. Preservada e **travada por teste**, com a divergência registrada para decisão do dono do produto.
- **D-06 — campos `bairro`/`cidade`/`pais` descartados.** Os payloads de exemplo enviam esses campos em `enderecos[]`; `Endereco.java` não os declara, o Jackson os ignora silenciosamente. Mantido e travado por teste de contrato — a assimetria entre D-04 (que valida `valor_total_itens` rigorosamente) e D-05/D-06 (que toleram inconsistência/perda de dado) é, ela própria, um indício registrado de que merece revisão de negócio.

---

## 3. Modernização de stack (SPEC-01)

- **Java 11 → 21**, **Spring Boot 2.6.2 → 4.1.1** (pulando a série 3.x inteira — decisão justificada: o suporte da série 3 termina em 30/06/2026, e uma tabela de exposição a *breaking changes* — `javax→jakarta`, `WebSecurityConfigurerAdapter`, `RestTemplate`, JUnit 4, Jackson 2→3 — mostrou que **nenhuma** delas afeta este código, então migrar em dois passos não se justificava).
- **Armadilha identificada e contornada:** sob o parent `2.6.2`, o BOM resolvia Lombok `1.18.22`, que **não compila em JDK 21**. Isso tornou a atualização de stack pré-requisito duro de qualquer outra mudança, e não apenas uma preferência de ordem.
- Pacote de testes realinhado de `br.com.itau.calculadoratributos` para `br.com.itau.geradornotafiscal`, espelhando o de produção (a divergência de pacotes citada no `CLAUDE.md` como pré-existente foi eliminada).
- Uso de recursos Java 21 avaliado e justificado item a item (não adotado só por ser novo): virtual threads sim (I/O bloqueante das notificações); `record` não (DTOs precisam de setters para o binding do Jackson e são contrato congelado); `StructuredTaskScope` não (ainda *preview*).

---

## 4. Arquitetura: de "quatro chamadas com `new`" a outbox transacional

Esta foi a maior mudança estrutural, entregue em duas ondas:

### 4.1 SPEC-03 — Núcleo hexagonal

```
domain/
├── aliquota/    (AliquotaPolicy + 4 implementações, CalculadoraAliquota, CalculadoraTributoItem — plain Java, sem Spring/Jackson)
└── frete/       (CalculadoraFrete)
application/
├── GerarNotaFiscalService     (@Service, orquestra o caso de uso)
├── config/DomainConfig        (composition root do domínio)
└── port/{in,out}/             (portas de entrada e saída)
adapter/
├── in/web/{GeradorNFController, ApiExceptionHandler}
└── out/{estoque,registro,entrega,financeiro}/   (adaptadores das notificações — removidos em SPEC-07)
```

`model/` permanece intocado — os DTOs são POJOs anêmicos e o contrato é congelado (P2); introduzir mapeadores 1:1 sem regra de tradução seria indireção sem ganho.

### 4.2 SPEC-07 — Outbox DynamoDB substitui as quatro chamadas (D-07, estado final)

Ao concluir o cálculo da nota, o serviço grava **um único registro transacional** no DynamoDB (`nota_fiscal_processamento`: `idNotaFiscal`, `payloadEvento` serializado, `status=PENDENTE`, `criadoEm`) e responde ao cliente — sem esperar nenhuma das quatro áreas. As quatro portas de notificação síncronas (`Estoque/Registro/Entrega/FinanceiroNotificacaoPort`) e seus adaptadores foram **removidos**, não substituídos um a um.

**Por quê ir direto ao estado-alvo, sem dupla-escrita:** o RFC original previa manter as quatro chamadas em paralelo ao outbox por um período de transição. Essa transição só faria sentido para validar consumidores novos — que são, por definição, times fora deste repositório. Sem a Lambda relay e os consumer groups (infraestrutura real, fora de escopo), não há como validar isso incrementalmente aqui; manter as quatro chamadas só para removê-las depois violaria P1.

O que vem depois do outbox (DynamoDB Streams → Lambda → Kafka/MSK → consumer groups por time → Saga coordinator para compensação) é **proposta arquitetural documentada em RFC-0001/ADR-0001, não código deste repositório** — ver seção 8.

**Execução local sem AWS real (P4):** o outbox aponta para **DynamoDB Local** via `docker-compose.yml` — contêiner, sem credencial, sem custo. Não é "AWS real"; é uma dependência de infraestrutura local declarada, no mesmo sentido em que um Postgres seria para um serviço com persistência relacional.

---

## 5. Segurança e validação de entrada (SPEC-04)

- **Autenticação JWT obrigatória e *fail-closed* por padrão** — a cadeia protegida (`oauth2ResourceServer` + `authenticated()`) é o **default sem `@Profile`**; um perfil de ambiente novo (ex.: `staging`) herda a postura protegida automaticamente, em vez de depender de alguém lembrar de adicionar o nome à lista certa. A cadeia permissiva é a exceção explícita, restrita a `local`/`test`. Justificativa: segmentação de rede (API Gateway na borda) não é autorização — um VPC Link mal configurado ou um *security group* com drift de IaC bastam para expor o serviço sem passar pelo autorizer.
- Os testes de segurança **ativam a cadeia real** (`@ActiveProfiles("dev")` + `@MockitoBean JwtDecoder`), não a permissiva — senão a proteção nunca seria de fato exercitada. Há também um teste explícito sob um perfil não previsto para provar a postura *fail-closed*.
- **Bean Validation** nas cinco classes de `model/` (`Pedido`, `Item`, `Destinatario`, `Documento`, `Endereco`), atuando sobre o **valor** já desserializado — nenhum `@JsonProperty` foi tocado (P2). Corrige regressões reais: `NullPointerException` em `destinatario.enderecos` nulo agora vira `400`; um `Pedido` com centenas de milhares de itens agora é rejeitado por `@Size(max = 500)` (fecha um vetor de DoS trivial).
- **D-04 — consistência de `valor_total_itens`:** esse campo dirige a escolha de faixa de alíquota e era aceito sem conferência — um cliente podia informar um total menor que a soma real dos itens e pagar imposto de faixa inferior (evasão fiscal por API). Passou a ser validado contra `Σ(valor_unitario × quantidade)` com tolerância de arredondamento, rejeitando com `400`. Os dois payloads de exemplo já satisfazem a invariante, o que indica que ela é real, não inventada.
- Troca de primitivos por wrappers (`int`→`Integer`, `double`→`Double`) nos campos onde isso importa — necessário porque um primitivo tem default indistinguível de "campo ausente", tornando `@NotNull` inócuo.
- `ApiExceptionHandler` (criado em SPEC-03 com um handler) foi **estendido**, não duplicado, com handlers para erro de validação e payload malformado — sempre devolvendo `ResponseEntity` com status explícito (devolver o corpo de erro sem `ResponseEntity` produziria `200` com corpo de erro, um erro fácil de cometer e sutil de notar em revisão).

---

## 6. Observabilidade e configuração por ambiente (SPEC-05)

- **Actuator em porta de management separada** (`8081`), isolado da porta de negócio (`8080`) — o controle de quem pode raspar métricas passa a ser de rede (security group / rede do compose), sem precisar de exceção de rota na cadeia de segurança.
- **Métricas de fluxo:** `nota_fiscal_geracao_seconds` (timer), `nota_fiscal_itens_processados` (distribution summary — também sinal de regressão do bug de vazamento de estado, caso reaparecesse), `nota_fiscal_geradas_total`/`nota_fiscal_falhas_total` (counters).
- **Métrica por adaptador de saída via aspecto único** (`MetricasAdaptadorSaidaAspect`, `@Around` sobre `adapter.out..*`) — responde "qual integração está lenta ou falhando" sem quatro trechos de instrumentação duplicados.
- **Log estruturado em JSON** com `idNotaFiscal`/`idPedido` em MDC (usando o *Structured Logging* nativo do Spring Boot 4.1, sem `logback-spring.xml` manual). Regra de LGPD aplicada e coberta por teste: **nenhum log emite o payload completo de `Pedido`/`Destinatario`**, que carrega CPF/CNPJ, nome e endereço.
- **Perfis canônicos:** `local`, `dev`, `staging`, `prod` (+ `test` para a suíte) — um conjunto único, referenciado por SPEC-04 (postura de segurança) e SPEC-06 (matriz de promoção), evitando que um perfil novo fique com configuração indefinida.
- **Sem credencial nem endpoint AWS literal** em nenhum `application*.properties` versionado — verificado por grep no CI.
- `docker-compose.yml` sobe aplicação + DynamoDB Local + Prometheus + Grafana, com datasource e dashboard **provisionados por arquivo** (não montados à mão pela UI).

---

## 7. CI/CD (SPEC-06)

Pipeline em `.github/workflows/ci.yml`, dois jobs:

1. **`build-and-test`** — `./mvnw -B clean verify` em JDK 21 (mesmo comando do desenvolvedor local), verificação estática de segredo (`grep` bloqueando `arn:aws:`/`amazonaws.com`/chave `AKIA...`), scan de dependências **Trivy** (SCA).
2. **`build-image`** (depende do primeiro) — build via Paketo buildpacks (`spring-boot:build-image`, sem `Dockerfile` manual — evita duas fontes de verdade para a imagem base), **smoke test por readiness HTTP real** (`/actuator/health/readiness`, não `grep` em log — um log "Started" passa mesmo com a aplicação degradada), scan de imagem Trivy.

**Decisão de segurança de cadeia de suprimentos:** o scan Trivy não usa a Action de terceiro `aquasecurity/trivy-action` — esse repositório sofreu um comprometimento real (76 de 77 tags reescritas para apontar a commits maliciosos, ver aviso da Aqua Security). Em vez disso, o CI invoca a imagem oficial `aquasec/trivy` fixada por **digest completo** via `docker run`.

**Achado real corrigido no processo:** o scan de dependências apontou 3 CVEs CRITICAL em `tomcat-embed-core:11.0.24` (versão trazida pelo BOM do Spring Boot 4.1.1), corrigido fixando `<tomcat.version>11.0.25</tomcat.version>` no `pom.xml`.

**Deliberadamente fora do workflow executável** (documentado em prosa em `SPEC-06`, não como YAML que rodaria): push para ECR e deploy real — exigiriam conta AWS e credenciais que este desafio não provisiona (P4); incluir esses passos produziria um workflow permanentemente vermelho. Estratégia de deployment (rolling update com *circuit breaker* do ECS), rollback (task definitions imutáveis por `git sha`) e matriz de promoção (`dev`→`staging`→`prod`, aprovação humana só em `prod`) estão documentadas no RFC-0001/SPEC-06.

---

## 8. Arquitetura produtiva proposta (RFC-0001 / ADR-0001)

Documento separado do código, como o desafio pede. Resumo da decisão central: **separar duas responsabilidades hoje fundidas numa única chamada HTTP** — (1) calcular e devolver a nota fiscal (rápido, determinístico, sem I/O externo — já implementado, é o estado atual do código) e (2) avisar Estoque/Registro/Entrega/Financeiro (efeitos colaterais em sistemas de terceiros, devem ser assíncronos e precisam de compensação quando um falha depois que os outros já confirmaram).

Estado da implementação: **o outbox (passo 3) já está em código** (SPEC-07). O restante é proposta:

- DynamoDB Streams → Lambda de relay → tópico Kafka/MSK `notafiscal.emitida.v1` (particionado por `idNotaFiscal`, nunca por campo de baixa cardinalidade como `Regiao`, para evitar *hot partitions*).
- Quatro consumer groups independentes, um por time/conta AWS (Estoque, Registro, Entrega, Financeiro), cada um com seus próprios tópicos de retry/DLQ.
- **Saga orquestrada** (Kafka Streams / Spring Kafka com state store, não AWS Step Functions — decisão revisada porque o backbone virou Kafka multi-conta) consumindo os 4 tópicos de *outcome*, decidindo compensação quando uma etapa falha definitivamente ou nunca confirma.
- Orquestração escolhida em vez de coreografia pura porque as quatro notificações não têm dependência sequencial entre si — o valor buscado é um único lugar com o estado agregado ("quantas confirmaram, o que precisa compensar"), que em coreografia pura teria que ser construído à parte de qualquer forma.

O documento cobre também, com trade-offs explícitos: API Gateway + Cognito na borda, VPC/rede, ECS Fargate multi-AZ + ALB, escalabilidade/HA, observabilidade (CloudWatch + X-Ray), persistência (outbox DynamoDB) e resiliência (circuit breaker de deployment, retry/DLQ por consumidor).

---

## 9. Estratégia de testes

### 9.1 Rede de testes de regressão (SPEC-02) — o que autorizou a reescrita

Ancorada no único *seam* estável durante toda a refatoração: o contrato HTTP (`POST /api/pedido/gerarNotaFiscal`) e as classes de `model/`. Testar contra `GeradorNotaFiscalServiceImpl` teria sido inútil — SPEC-03 apaga essa classe.

- **16 combinações** de tipo de pessoa × regime × faixa de alíquota + fronteiras exatas de cada limite.
- **6 combinações** de frete (5 regiões + ausência de endereço de entrega).
- Isolamento nos três modos: idempotência, não-vazamento (pedido grande → pedido pequeno), concorrência real (`CountDownLatch`).
- Contrato de entrada e saída validado por **conjunto de nomes de campo** (não JSON exato — `id_nota_fiscal`/`data` mudam a cada chamada), o que também falha imediatamente se um `@JsonProperty` for alterado.
- Comportamentos deliberadamente preservados (D-05, D-06) travados por teste com comentário explicando a preservação.
- Suíte E2E em **Robot Framework** (`robot/`), com `Suite Setup` aguardando a aplicação subir.

Esta suíte passou **sem edição alguma** durante SPEC-03 a SPEC-05, exceto o único caso autorizado (D-03).

### 9.2 Testes unitários de domínio (SPEC-03)

Uma classe de teste por `AliquotaPolicy`, `CalculadoraFrete`, `CalculadoraTributoItem`, `CalculadoraAliquota` — plain Java, sem Spring, cobrindo faixas e fronteiras isoladamente.

### 9.3 Testes de segurança e validação (SPEC-04)

Cadeia JWT real exercitada (`401` sem token, `401` com token inválido, `401` em perfil não previsto — teste de postura *fail-closed*), validação de cada constraint de Bean Validation, D-04 nos dois sentidos.

### 9.4 Testes de observabilidade e outbox (SPEC-05/07)

Métricas via Actuator/Prometheus, log estruturado sem dado pessoal (asserção sobre `ILoggingEvent`, não inspeção manual), `DynamoDbNotaFiscalProcessamentoAdapterTest` contra DynamoDB Local real via **Testcontainers**.

### 9.5 Evidência de performance versionada — k6 (`k6/`)

Quatro cenários reexecutáveis, com **duas execuções documentadas** em `k6/RESULTADOS.md`, contra as duas versões do serviço:

**1. SPEC-03** — núcleo hexagonal com as quatro notificações síncronas paralelizadas via virtual threads (execução real, k6 v2.2.0, Java 21 Corretto, 2026-09-21):

| Cenário | O que prova | Resultado |
|---|---|---|
| A — 1 item vs. 6 itens | Sem penalidade de `+5s` | p95 512ms vs. 508ms (antes: salto para ~5,35s) |
| B — execuções sucessivas | Sem degradação por vazamento de estado | p95 estável entre 1º e último terço (508ms → 505ms) |
| C — bloco de notificações | Paralelo, não sequencial | p95 ~505ms (não ~1480ms da soma) |
| D — carga concorrente (1→50 VUs) | Virtual threads não enfileiram sob concorrência | p95 ~504ms, 0% de falhas em 2100 requisições |

**2. SPEC-07** — mesmos quatro scripts, sem edição, contra o outbox transacional (DynamoDB Local), reexecutados em 2026-09-21:

| Cenário | SPEC-03 (p95) | SPEC-07 (p95) | Redução |
|---|---|---|---|
| A — 1 item | 512.29ms | 13.74ms | ~37× |
| A — 6 itens | 507.62ms | 9.08ms | ~56× |
| B — 1º terço | 508.63ms | 19.29ms | ~26× |
| B — último terço | 504.94ms | 12.90ms | ~39× |
| C — bloco de notificações / outbox | 504.57ms | 6.67ms | ~76× |
| D — 50 VUs concorrentes | 503.83ms | 48.51ms | ~10× |
| D — taxa de falha | 0,00% (2100/2100) | 0,00% (39416/39416) | inalterada |

**Leitura do resultado, não só o número:** o ganho não veio de acelerar latência simulada (P3 continua respeitado — os `Thread.sleep` das quatro integrações não foram "otimizados", as próprias chamadas deixaram de existir no caminho síncrono, ver seção 4). O caso D tem o menor ganho relativo (~10×, contra ~26–76× dos demais) porque é o único cenário que introduz um recurso externo real no caminho crítico — o DynamoDB Local, um processo único, vira gargalo sob 50 VUs concorrentes, diferente das antigas quatro chamadas com `Thread.sleep` em memória, sem contenção de recurso nenhum. Em produção esse papel é do DynamoDB gerenciado (particionado, escala horizontal), não de um contêiner de desenvolvimento. A leitura correta não é "56× mais rápido em produção" — é confirmação de que a resposta HTTP deixou de estar limitada pela integração mais lenta.

Detalhamento completo, com os quatro cenários e a tabela de comparação por cenário, em [`k6/RESULTADOS.md`](k6/RESULTADOS.md). Limiares por ordem de grandeza sobre `p95`, nunca milissegundos exatos — para não ficarem instáveis em CI compartilhado.

---

## 10. Como executar o sistema localmente

### 10.1 Só a aplicação (sem observabilidade nem outbox real)

```bash
./mvnw spring-boot:run
```

Sobe em `http://localhost:8080` (perfil `local` por padrão). **Atenção:** desde SPEC-07 a aplicação grava o outbox no DynamoDB a cada requisição de negócio — sem um DynamoDB acessível em `DYNAMODB_ENDPOINT_OVERRIDE`, `POST /api/pedido/gerarNotaFiscal` responde `500`. Para testar o endpoint de ponta a ponta, suba ao menos o DynamoDB Local:

```bash
docker run -d -p 8000:8000 amazon/dynamodb-local -jar DynamoDBLocal.jar -inMemory -sharedDb
DYNAMODB_ENDPOINT_OVERRIDE=http://localhost:8000 DYNAMODB_AUTO_CREATE_TABLE=true ./mvnw spring-boot:run
```

Smoke test manual com os payloads de exemplo (nota: o diretório é `paylods`, typo pré-existente mantido):

```bash
curl -X POST http://localhost:8080/api/pedido/gerarNotaFiscal \
  -H "Content-Type: application/json" \
  -d @src/main/resources/paylods/teste-pf.json

curl -X POST http://localhost:8080/api/pedido/gerarNotaFiscal \
  -H "Content-Type: application/json" \
  -d @src/main/resources/paylods/teste-pj-simples.json
```

Perfil `local` usa a cadeia de segurança **permissiva** (sem exigir JWT) — é a única exceção deliberada; qualquer outro perfil exige `Authorization: Bearer <jwt>`.

### 10.2 Stack completa (aplicação + DynamoDB Local + Prometheus + Grafana)

```bash
cp .env.example .env
./mvnw spring-boot:build-image
docker compose up
```

- Aplicação: `http://localhost:8080` (negócio) / `http://localhost:8081` (Actuator: `/actuator/health/liveness`, `/readiness`, `/prometheus`)
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000` (dashboard já provisionado)

### 10.3 Empacotar e rodar o jar

```bash
./mvnw clean package
java -jar target/*.jar
```

### 10.4 Build de imagem OCI (Paketo buildpacks, sem Dockerfile)

```bash
./mvnw spring-boot:build-image
docker run -p 8080:8080 -p 8081:8081 -e SPRING_PROFILES_ACTIVE=local geradornotafiscal:0.0.1-SNAPSHOT
```

---

## 11. Como executar os testes localmente

### 11.1 Suíte completa (unitário + integração + contrato)

```bash
./mvnw clean verify
```

> Requer Docker disponível — `DynamoDbNotaFiscalProcessamentoAdapterTest` usa Testcontainers contra DynamoDB Local real.

### 11.2 Uma classe ou método específico

```bash
./mvnw test -Dtest=GerarNotaFiscalCaracterizacaoTest
./mvnw test -Dtest=AliquotaPessoaFisicaPolicyTest#deveAplicar12PorCentoAte2000
```

Classes de teste relevantes por camada:

| Camada | Classes |
|---|---|
| Caracterização/contrato HTTP | `GerarNotaFiscalCaracterizacaoTest`, `GerarNotaFiscalContratoTest` |
| Segurança | `EndpointSegurancaTest`, `PerfilNaoPrevistoFailClosedTest` |
| Validação | `PedidoValidacaoTest`, `BeanValidationTest` |
| Domínio (alíquota/frete) | `Aliquota*PolicyTest`, `CalculadoraAliquotaTest`, `CalculadoraTributoItemTest`, `CalculadoraFreteTest` |
| Caso de uso | `GerarNotaFiscalServiceTest` |
| Observabilidade | `ObservabilidadeTest`, `MetricasAdaptadorSaidaAspectTest` |
| Outbox | `DynamoDbNotaFiscalProcessamentoAdapterTest` |

### 11.3 Testes de performance (k6)

Pré-requisito: [k6](https://k6.io/docs/get-started/installation/) instalado, aplicação de pé em `http://localhost:8080`.

```bash
./mvnw spring-boot:run &
k6 run k6/scenario-a-item-count.js
k6 run k6/scenario-b-successive-runs.js
k6 run k6/scenario-c-notification-block.js
k6 run k6/scenario-d-concurrent-load.js
```

Resultados de referência em [`k6/RESULTADOS.md`](k6/RESULTADOS.md).

### 11.4 Testes E2E (Robot Framework)

```bash
./mvnw spring-boot:run &
pip install -r robot/requirements.txt
robot -d robot/results robot/tests
```

Suítes: `gerar_nota_fiscal.robot` (caminho feliz para PF e PJ), `pedido_invalido.robot` e `pedido_border_cases.robot` (validação e fronteiras).

---

## 12. O que foi deliberadamente deixado fora do escopo

Registrado explicitamente para não ser confundido com esquecimento:

- **Provisionamento de infraestrutura AWS real** (IaC/Terraform/CDK) — P4; RFC-0001 é proposta, não infraestrutura aplicada.
- **Publicação Kafka, consumer groups por time, Saga coordinator, `GET /status`** — dependem de infraestrutura AWS real (Lambda, MSK) fora deste código-fonte; documentados como próximos passos do caminho de migração incremental.
- **Correção de D-05 (tributo ignora quantidade) e D-06 (campos descartados)** — mudariam o valor de toda nota emitida ou o contrato de saída sem mandato de negócio; preservados e travados por teste para decisão consciente do dono do produto.
- **Push de imagem para ECR / job de deploy no CI** — exigiriam credencial AWS que este desafio não provisiona; documentados em prosa, não como workflow executável.
- **Rate limiting** — delegado à borda (API Gateway), fora do código da aplicação; o que compete à aplicação (limite de tamanho de uma requisição única) foi implementado (`@Size(max = 500)`).
- **Autorização por escopo/papel** — o endpoint é único e o desafio não define papéis distintos de consumidor.
- **Scan de segredos no histórico do git (Gitleaks) e gates de qualidade estática (SpotBugs/PMD/Checkstyle)** — registrados como evolução futura possível, não implementados nesta entrega.

---

## 13. Mapa de leitura dos documentos

| Pergunta | Onde encontrar |
|---|---|
| "Por que essa decisão específica foi tomada?" | `docs/sdd/CONSTITUICAO.md` (D-01 a D-07) |
| "Qual é o requisito exato e como foi verificado?" | `docs/sdd/specs/SPEC-0N-*.md` correspondente |
| "Como isso roda em produção na AWS?" | `docs/rfc/RFC-0001-arquitetura-produtiva-aws.md` |
| "Por que Saga orquestrada e Kafka, e não outra coisa?" | `docs/adr/ADR-0001-notificacoes-assincronas-saga-orquestrada.md` |
| "Qual é a regra de negócio por trás disso?" | `docs/VISAO-DE-NEGOCIO.md` |
| "Quais números provam o ganho de performance?" | `k6/RESULTADOS.md` |
