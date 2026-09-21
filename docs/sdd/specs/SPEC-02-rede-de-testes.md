# SPEC-02 — Rede de testes no seam estável

| | |
|---|---|
| **Depende de** | SPEC-01 (pacote de testes já realinhado) |
| **Habilita** | SPEC-03 — esta spec verde é o portão que autoriza a refatoração |
| **Decisões aplicáveis** | D-03 (caracteriza o comportamento atual), D-05, D-06 |
| **Altera comportamento observável?** | Não — nenhuma regra de negócio é tocada |
| **Constituição** | [P5](../CONSTITUICAO.md#p5--um-teste-que-muda-junto-com-o-código-que-vigia-não-prova-nada), [P2](../CONSTITUICAO.md#p2--o-contrato-json-é-imutável) |

## Objetivo

Construir uma rede de testes ancorada no contrato HTTP — o único ponto que não muda durante a refatoração — para que a reescrita do núcleo (SPEC-03) seja provada equivalente sem que um único teste precise ser editado junto com o código que ele protege.

## O problema que esta spec resolve

Um teste de refatoração só tem valor se **não for editado na mesma mudança que ele deveria vigiar** (P5). Um teste reescrito junto com o código não prova nada: prova apenas que o autor conseguiu fazer os dois concordarem.

Daí a importância de escolher o *seam* certo. Testar contra `GeradorNotaFiscalServiceImpl` seria inútil — é uma classe que SPEC-03 apaga, então a suíte inteira teria que ser reapontada no mesmo PR da refatoração. Nesta aplicação há dois pontos estáveis, e ambos sobrevivem a SPEC-03 sem alteração:

| Elemento | Muda em SPEC-03? | Serve de seam? |
|---|---|---|
| `POST /api/pedido/gerarNotaFiscal` (rota, verbo, JSON) | Não — contrato congelado (P2) | **Sim** |
| `model/*` (`Pedido`, `NotaFiscal`, `Item`, `Destinatario`, …) | Não — `@JsonProperty` congelado | **Sim** (montagem de fixture) |
| `GeradorNotaFiscalService` (interface) | **Sim** — vira `GerarNotaFiscalUseCase` | Não |
| `GeradorNotaFiscalServiceImpl` | **Sim** — deixa de existir | Não |
| `CalculadoraAliquotaProduto` | **Sim** — move para `domain/`, é renomeada | Não |

Logo: **toda a suíte de caracterização entra via `MockMvc` sobre o endpoint**, montando o `Pedido` com as classes de `model/` (estáveis) e serializando com o `ObjectMapper`. Fica legível como um teste de unidade e imune à reorganização de pacotes.

Os testes de unidade *de domínio* (uma classe por `AliquotaPolicy`) são escritos em **SPEC-03**, contra classes que só existem lá. Não há duplicação: a suíte desta spec vigia comportamento de ponta a ponta; a de SPEC-03 documenta cada regra isoladamente.

## Contexto verificado — lacunas de cobertura

**Alíquota** — 4 famílias de regra × 4 faixas = 16 combinações. Hoje 2 têm teste.

| Tipo pessoa | Regime | Faixa (`valorTotalItens`) | Alíquota |
|---|---|---|---|
| FISICA | — | `< 500` / `<= 2000` / `<= 3500` / `> 3500` | 0 / 0,12 / 0,15 / 0,17 |
| JURIDICA | SIMPLES_NACIONAL | `< 1000` / `<= 2000` / `<= 5000` / `> 5000` | 0,03 / 0,07 / 0,13 / 0,19 |
| JURIDICA | LUCRO_REAL | `< 1000` / `<= 2000` / `<= 5000` / `> 5000` | 0,03 / 0,09 / 0,15 / 0,20 |
| JURIDICA | LUCRO_PRESUMIDO | `< 1000` / `<= 2000` / `<= 5000` / `> 5000` | 0,03 / 0,09 / 0,16 / 0,20 |

Cada limite entra também como caso de fronteira exata (ex.: `2000.0` para FISICA cai em 12%, porque o código usa `<=`).

**Frete** — 6 combinações, das quais só `SUDESTE` tem teste hoje:

| Região do endereço de entrega | Multiplicador |
|---|---|
| `NORTE` / `NORDESTE` / `CENTRO_OESTE` / `SUDESTE` / `SUL` | 1,08 / 1,085 / 1,07 / 1,048 / 1,06 |
| nenhum endereço com finalidade `ENTREGA`/`COBRANCA_ENTREGA` | frete zerado |

**Contrato** — nenhum teste hoje serializa ou desserializa JSON; `GeradorNFController` não tem teste algum.

**Configuração morta** — `GeradorNotaFiscalServiceImplTest` declara `@Mock CalculadoraAliquotaProduto` sem alvo de injeção: a produção cria a instância como variável local (`GeradorNotaFiscalServiceImpl.java:23`).

**Limiar real do bug de performance** — `> 5` itens (`EntregaIntegrationPort.java:10`), ou seja, dispara a partir de **6**, não de 7 como a redação "mais de 6 itens" do enunciado sugere.

## Requisitos

**REQ-2.1 — Suíte de caracterização via HTTP, sem acoplamento a classes que somem.**
Escrita antes de SPEC-03, exercitando `POST /api/pedido/gerarNotaFiscal` via `MockMvc`, sem nenhuma referência a `GeradorNotaFiscalServiceImpl`, `GeradorNotaFiscalService` ou `CalculadoraAliquotaProduto`.
*Verificação:* `grep -rn "GeradorNotaFiscalServiceImpl\|CalculadoraAliquotaProduto" src/test` não retorna nada na suíte nova.

**REQ-2.2 — Cobertura completa de alíquota e frete.**
As 16 combinações de tipo de pessoa/regime/faixa mais as fronteiras exatas de cada limite, e as 6 combinações de frete.

**REQ-2.3 — Isolamento coberto nos três modos.**
Idempotência (duas requisições iguais → mesmo resultado), não-vazamento (pedido pequeno após pedido grande não traz itens do anterior) e concorrência (pedidos distintos não se contaminam).

**REQ-2.4 — Contrato validado nos quatro níveis de aninhamento.**
Entrada: os dois payloads de exemplo desserializam em `Pedido` sem perda de campo mapeado. Saída: conjunto exato de campos em `NotaFiscal`, `itens[]`, `destinatario`, `destinatario.enderecos[]` e `destinatario.documentos[]`.

**REQ-2.5 — Fronteira 5/6 itens coberta funcionalmente, sem asserção de tempo.**
Tempo é responsabilidade dos cenários k6 de SPEC-03; aqui só o comportamento.

**REQ-2.6 — Comportamentos deliberadamente preservados travados por teste.**
D-05 (tributo ignora `quantidade`) e D-06 (campos descartados), cada um com comentário no código do teste explicando que a preservação é deliberada.

**REQ-2.7 — `RegimeTributacaoPJ.OUTROS` caracterizado com o comportamento atual.**
Marcado no código do teste como o **único** caso que SPEC-03 tem permissão de alterar (D-03).

**REQ-2.8 — Suíte E2E em Robot Framework.**
Em `robot/`, com `Suite Setup` aguardando a aplicação subir, e instruções de execução documentadas.

**REQ-2.9 — Portão para SPEC-03.**
`./mvnw clean verify` verde **antes** de SPEC-03 começar.

## Desenho

### Estrutura de destino

```
src/test/java/br/com/itau/geradornotafiscal/
├── GeradorNotaFiscalApplicationTests.java        (renomeado em SPEC-01)
└── adapter/in/web/
    ├── GerarNotaFiscalCaracterizacaoTest.java    (alíquota, frete, isolamento, 5/6 itens)
    └── GerarNotaFiscalContratoTest.java          (conjunto de campos entrada/saída)
robot/
├── requirements.txt
├── resources/api.resource
└── tests/gerar_nota_fiscal.robot
```

O diretório de destino já é o de SPEC-03 (`adapter/in/web/`) — os arquivos nascem no lugar final mesmo antes de o pacote de produção existir, porque o pacote de teste é independente do de produção (P1).

### Padrão de teste

`@ParameterizedTest` + `@MethodSource` com a tabela de decisão como fonte de dados. Dezenas de métodos quase idênticos reproduziriam, na suíte, o mesmo anti-padrão de regras duplicadas que o README aponta no código de produção.

```java
@ParameterizedTest(name = "{0}/{1} valorTotalItens={2} -> aliquota={3}")
@MethodSource("combinacoesAliquota")
void deveAplicarAliquotaPorTipoPessoaRegimeEFaixa(
        TipoPessoa tipoPessoa, RegimeTributacaoPJ regime,
        double valorTotalItens, double aliquotaEsperada) throws Exception {

    Pedido pedido = umPedido(tipoPessoa, regime, valorTotalItens, Regiao.SUDESTE);
    double valorUnitario = pedido.getItens().get(0).getValorUnitario();

    mockMvc.perform(post("/api/pedido/gerarNotaFiscal")
                    .contentType(APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(pedido)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.itens[0].valor_tributo_item")
                    .value(closeTo(valorUnitario * aliquotaEsperada, 0.0001)));
}
```

O `jsonPath` sobre o nome snake_case é deliberado: a asserção passa pelo `@JsonProperty`, então também vigia o contrato de saída de graça.

As fixtures usam **literais `double`** (`400d`, `1500.0`), nunca `int` — SPEC-04 troca os primitivos de `model/` por wrappers, e Java não faz boxing seguido de widening. Escrever assim desde já é o que impede que SPEC-04 obrigue a editar esta suíte (P5).

### Contrato: comparar conjunto de campos, não JSON inteiro

`id_nota_fiscal` (UUID) e `data` (timestamp) mudam a cada chamada — comparação exata exigiria mascaramento. Comparar o **conjunto de nomes de campo** captura o que o requisito pede de forma determinística, e falha imediatamente se um `@JsonProperty` for adicionado, removido ou renomeado.

```java
assertThat(campos(raiz)).containsExactlyInAnyOrder(
        "id_nota_fiscal", "data", "valor_total_itens", "valor_frete", "itens", "destinatario");
assertThat(campos(raiz.path("itens").get(0))).containsExactlyInAnyOrder(
        "id_item", "descricao", "valor_unitario", "quantidade", "valor_tributo_item");
assertThat(campos(raiz.path("destinatario"))).containsExactlyInAnyOrder(
        "nome", "tipo_pessoa", "regime_tributacao", "documentos", "enderecos");
assertThat(campos(raiz.path("destinatario").path("enderecos").get(0))).containsExactlyInAnyOrder(
        "cep", "logradouro", "numero", "estado", "complemento", "finalidade", "regiao");
assertThat(campos(raiz.path("destinatario").path("documentos").get(0))).containsExactlyInAnyOrder(
        "numero", "tipo");
```

Nenhuma dependência Maven nova: `spring-boot-starter-test` já traz `MockMvc`, Jackson e `json-path`.

Esse mesmo teste é o que, em SPEC-04, prova que as constraints de classe (`isValorTotalItensConsistente`, `isRegimeTributacaoInformadoParaJuridica`) não vazaram campo novo na resposta — sem precisar ser editado.

### Comportamentos a travar deliberadamente

Três constatações levantadas na leitura do código. Não são corrigidas aqui — são **fixadas por teste**, para que qualquer mudança futura seja consciente.

#### D-05 — comportamento fiscal preservado deliberadamente

`CalculadoraAliquotaProduto.java:14` calcula `valorTributo = valorUnitario × aliquota` — **sem multiplicar pela quantidade**. No payload PJ de exemplo (8 unidades de R$ 730 a 19%), o tributo do item sai R$ 138,70 em vez de R$ 1.109,60.

Isso é provavelmente incorreto do ponto de vista fiscal, e [`VISAO-DE-NEGOCIO.md`](../../VISAO-DE-NEGOCIO.md) é explícita em que erro aqui "é um problema de compliance, não apenas um bug de sistema". Mas o README não reporta esse sintoma, e alterar a fórmula mudaria o valor de toda nota emitida sem mandato de negócio. Decisão [D-04/D-05](../CONSTITUICAO.md#d-05--valortributoitem-continua-ignorando-quantidade): **preservar e travar**, com a divergência registrada para decisão do dono do produto.

```java
@Test
void tributoPorItemIgnoraQuantidade_comportamentoAtualTravado() {
    // Divergência conhecida (D-05): a fórmula não multiplica pela quantidade.
    // Travada de propósito — mudança exige decisão de negócio, não refatoração.
}
```

#### D-06 — campos de entrada descartados

`teste-pf.json` e `teste-pj-simples.json` enviam `bairro`, `cidade` e `pais` dentro de `enderecos[]`. `Endereco.java` não declara nenhum dos três: o Jackson os ignora e, como o `Destinatario` de entrada é ecoado na resposta, eles somem da saída.

Mapeá-los acrescentaria três campos ao JSON de resposta. Decisão: **não mapear nesta entrega**, travando o conjunto de campos de `enderecos[]` no teste de contrato acima, e registrar a pendência. O teste é o que impede que isso volte a passar despercebido.

#### `RegimeTributacaoPJ.OUTROS`

Hoje nenhum ramo trata esse valor e a nota sai com `itens: []`, sem erro. A suíte caracteriza o comportamento **atual**:

```java
@Test
void regimeOutrosProduzNotaComItensVazios_atual() { /* ... */ }
```

Este é o **único teste que SPEC-03 tem permissão para alterar**, e apenas porque [D-03](../CONSTITUICAO.md#d-03--regimetributacaopjoutros-passa-a-ser-erro-explícito) decide conscientemente trocar o comportamento por `422`. A alteração acontece no mesmo commit que implementa D-03, nunca como "ajuste para o teste passar".

### Suíte E2E — Robot Framework

Fora de `src/`, ciclo de execução próprio. Como a suíte depende da aplicação estar de pé, inclui `Suite Setup` de espera:

```robotframework
*** Settings ***
Library           RequestsLibrary
Library           OperatingSystem
Suite Setup       Aguardar Aplicacao Disponivel

*** Variables ***
${BASE_URL}       http://localhost:8080
${PAYLOADS_DIR}   ${CURDIR}/../../src/main/resources/paylods

*** Keywords ***
Aguardar Aplicacao Disponivel
    Wait Until Keyword Succeeds    30s    2s    Create Session    api    ${BASE_URL}

*** Test Cases ***
Gerar Nota Fiscal Para Pessoa Fisica
    ${payload}=    Get File    ${PAYLOADS_DIR}/teste-pf.json
    ${headers}=    Create Dictionary    Content-Type=application/json
    ${resp}=    POST On Session    api    /api/pedido/gerarNotaFiscal    data=${payload}    headers=${headers}
    Should Be Equal As Numbers    ${resp.status_code}    200
    Length Should Be    ${resp.json()}[itens]    1
```

Execução: `./mvnw spring-boot:run` e, em outro terminal, `pip install -r robot/requirements.txt && robot -d robot/results robot/tests`. A partir de SPEC-05 o `Suite Setup` passa a aguardar `/actuator/health/readiness` em vez de apenas abrir sessão.

**Testcontainers não entra nesta entrega**: não há banco nem mensageria a testar. O outbox/MSK do RFC-0001 é proposta arquitetural, não código entregue — introduzir Testcontainers agora seria dependência sem uso.

## Plano de execução

- [ ] Remover a configuração de mock morta de `GeradorNotaFiscalServiceImplTest` (`@Mock`/`@InjectMocks` de `CalculadoraAliquotaProduto`, que nunca é injetada).
- [ ] Criar a suíte de caracterização HTTP (`MockMvc`) cobrindo as 16 combinações tipo de pessoa/regime/faixa de alíquota, mais os valores de fronteira de cada limite.
- [ ] Cobrir na mesma suíte as 6 combinações de frete (5 regiões + ausência de endereço com finalidade `ENTREGA`/`COBRANCA_ENTREGA`).
- [ ] Caracterizar `RegimeTributacaoPJ.OUTROS` com o comportamento **atual** (`itens: []`), marcado para virar `422` em SPEC-03 (D-03).
- [ ] Travar por teste que `valorTributoItem` ignora `quantidade` (D-05).
- [ ] Teste de contrato de entrada: os dois payloads de exemplo desserializam em `Pedido` sem perda de campo mapeado.
- [ ] Teste de contrato de saída: conjunto exato de campos nos quatro níveis — inclui travar o descarte de `bairro`/`cidade`/`pais` (D-06).
- [ ] Testes de isolamento: idempotência, não-vazamento pedido-grande→pedido-pequeno, e concorrência com pedidos distintos.
- [ ] Teste funcional de 5 e 6 itens (fronteira real do código, `> 5`), sem asserção de tempo.
- [ ] Suíte Robot Framework em `robot/`, com `Suite Setup` aguardando a aplicação subir.
- [ ] Usar literais `double` em todas as fixtures numéricas.

## Verificação

1. Alíquota: 16 combinações + fronteiras exatas, via HTTP.
2. Frete: 5 regiões + ausência de endereço de entrega, via HTTP.
3. Caracterização de `RegimeTributacaoPJ.OUTROS` (comportamento atual).
4. Travamento de D-05 (tributo ignora quantidade) e D-06 (campos descartados).
5. Isolamento: idempotência; não-vazamento pedido-grande → pedido-pequeno; concorrência com pedidos distintos e conhecidos, usando `CountDownLatch` para garantir sobreposição real e asserções sobre conteúdo determinístico, não sobre ordem de chegada.
6. Fronteira de 5 e 6 itens (limiar real `> 5`), **sem** asserção de tempo.
7. Contrato de entrada: os dois payloads desserializam sem perda de campo mapeado.
8. Contrato de saída: conjunto exato de campos nos quatro níveis.
9. E2E Robot: um cenário por payload de exemplo.
10. **Portão:** `./mvnw clean verify` verde antes de SPEC-03 começar.

## Fora de escopo

- Qualquer alteração de regra de negócio ou de contrato JSON.
- Testes de unidade de domínio (`AliquotaPolicy`, `CalculadoraFrete`) — pertencem a SPEC-03, contra classes que só existem lá.
- Asserções de tempo — pertencem aos cenários k6 de SPEC-03.
- Testcontainers.
