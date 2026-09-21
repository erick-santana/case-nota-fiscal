# SPEC-03 — Núcleo alvo: domínio, portas/adaptadores, isolamento e performance

| | |
|---|---|
| **Depende de** | SPEC-01 (Java 21 para virtual threads), **SPEC-02 verde** (rede que autoriza a refatoração) |
| **Habilita** | SPEC-04 (adaptador web e `ApiExceptionHandler`), SPEC-05 (portas para o aspecto de métricas), SPEC-06 (nomes reais para o mapeamento AWS) |
| **Decisões aplicáveis** | D-01, D-02, D-03, D-05 |
| **Altera comportamento observável?** | **Sim, uma vez:** D-03 (`OUTROS` → `422`) |
| **Constituição** | [P1](../CONSTITUICAO.md#p1--nada-é-especificado-contra-um-estado-intermediário), [P2](../CONSTITUICAO.md#p2--o-contrato-json-é-imutável), [P3](../CONSTITUICAO.md#p3--as-latências-simuladas-são-o-cenário-não-o-problema), [P5](../CONSTITUICAO.md#p5--um-teste-que-muda-junto-com-o-código-que-vigia-não-prova-nada) |

> **Nota de revisão ([SPEC-07](./SPEC-07-outbox-dynamodb.md))**: as quatro portas/adaptadores de notificação síncrona descritos abaixo (`EstoqueNotificacaoPort`, `RegistroNotificacaoPort`, `EntregaNotificacaoPort`+`EntregaIntegrationPort`, `FinanceiroNotificacaoPort`) e D-01/D-02 foram **removidos e superados** por SPEC-07 — este documento permanece como registro histórico do que foi entregue nesta spec, não como descrição do código atual.

## Objetivo

Entregar um núcleo em que as regras de cálculo vivam isoladas de frameworks e as integrações fiquem atrás de portas, **já sem** os bugs de isolamento e de performance — de modo que cada requisição produza uma nota correta em tempo estável, e que novas regras ou uma troca de integração não exijam tocar na classe principal.

## O ponto central desta spec

Isolamento, performance e arquitetura são a mesma mudança, no mesmo método — não três passadas sobre ele. Escreve-se o código-alvo **uma vez** (P1): o estado compartilhado nunca é escrito, o `+5s` nunca é escrito, e a paralelização nasce com virtual threads.

Os três "bugs" do enunciado deixam de ser correções e passam a ser **propriedades do desenho**, verificadas pela rede de testes de SPEC-02, que não precisa ser tocada.

## Contexto verificado

`GeradorNotaFiscalServiceImpl.gerarNotaFiscal` (`service/impl/GeradorNotaFiscalServiceImpl.java:16-129`) concentra quatro responsabilidades:

| Responsabilidade | Linhas | Problema |
|---|---|---|
| Regras de alíquota | `25-38` (FISICA), `43-57` (SIMPLES), `58-71` (LUCRO_REAL), `72-86` (LUCRO_PRESUMIDO) | Quatro ramos quase-duplicados — mesma estrutura, só mudam limiares e percentuais |
| Regra de frete | `88-109` | Segunda árvore de decisão, independente da primeira, na mesma classe |
| Orquestração de I/O | `123-126` | Quatro `new`; não são beans, não passam por porta, impossíveis de mockar sem reflexão |
| Montagem do DTO | `114-121` | — |

**Estado compartilhado.** `CalculadoraAliquotaProduto.itemNotaFiscalList` é `static` (`service/CalculadoraAliquotaProduto.java:9`). `calcularAliquota` acrescenta a essa lista e **devolve a lista acumulada inteira** (linha 24). O `new CalculadoraAliquotaProduto()` por requisição não isola nada: o campo pertence à classe. A referência à lista global vaza direto para `NotaFiscal.itens` e daí para a resposta HTTP. É cumulativo e permanente pela vida do processo, e `ArrayList` não é thread-safe — sob requisições concorrentes há ainda perda de itens e `ConcurrentModificationException` em cima do vazamento.

Isso também explica a degradação ao longo do tempo: a lista cresce, `notaFiscal.getItens().size()` ultrapassa 5 mesmo para pedidos pequenos, e cada chamada passa a pagar a penalidade abaixo.

**Penalidade desproporcional.** `EntregaIntegrationPort.criarAgendamentoEntrega` (`port/out/EntregaIntegrationPort.java:10-14`) soma `Thread.sleep(5000)` quando `getItens().size() > 5` — ou seja, **a partir de 6 itens**, não de 7 como a redação "mais de 6 itens" do enunciado sugere. O braço de entrega salta de 350ms para 5350ms ao cruzar o limiar, sem relação com o custo real de uma chamada de agendamento. O `Thread.sleep(200)` da linha 16 é a latência simulada legítima e permanece (P3).

**Sequencialidade.** As quatro chamadas são independentes: `notaFiscal` já está montada antes do bloco, as quatro são `void` e nenhuma consome o retorno da outra.

| Integração | Latência simulada |
|---|---|
| `EstoqueService` | 380ms |
| `RegistroService` | 500ms |
| `EntregaService` + `EntregaIntegrationPort` | 150ms + 200ms |
| `FinanceiroService` | 250ms |

Soma sequencial: **1480ms** de latência artificial para qualquer pedido. Limitado pela mais lenta: **~500ms**. Redução de ~3× sem remover um único `Thread.sleep`.

**Tratamento de erro.** Cinco pontos (`EstoqueService.java:10-12`, `RegistroService.java:11-13`, `EntregaService.java:13-15`, `FinanceiroService.java:11-13`, `EntregaIntegrationPort.java:17-19`) fazem `throw new RuntimeException(e)` sobre `InterruptedException`, descartando o status de interrupção da thread e sem dizer qual integração falhou nem para qual nota.

**Porta de saída que não é porta.** `port/out/EntregaIntegrationPort.java` está no pacote certo mas é uma **classe concreta** com lógica, instanciada com `new` dentro de outra classe concreta (`EntregaService.java:12`). Nenhuma das duas é substituível.

## Requisitos

**REQ-3.1 — Isolamento entre requisições.**
Duas requisições consecutivas com o mesmo `Pedido` produzem o mesmo resultado; uma requisição pequena após uma grande não traz itens da anterior; requisições concorrentes não se contaminam.
*Verificação:* nenhum campo `static` mutável permanece em `src/main/java`; `CalculadoraTributoItem` não tem campo algum; testes de isolamento de SPEC-02 verdes.

**REQ-3.2 — Sem penalidade desproporcional por quantidade de itens.**
Pedidos com 6 ou mais itens não sofrem penalidade de tempo desproporcional, e o tempo de resposta não cresce ao longo de execuções sucessivas.
*Verificação:* `EntregaIntegrationAdapter` não contém nenhuma latência condicionada a `itens.size()`; cenários k6 A e B.

**REQ-3.3 — Notificações concorrentes, sem remover latência.**
As quatro notificações são disparadas concorrentemente sobre um executor de virtual threads (D-01) e aguardadas com agregação de falhas (D-02). Nenhuma latência simulada é removida ou reduzida (P3): 380/500/150+200/250ms permanecem.
*Verificação:* cenário k6 C — bloco de notificações da ordem da mais lenta (~500ms), não da soma (~1280ms).

**REQ-3.4 — Domínio de cálculo livre de framework.**
`domain/aliquota` e `domain/frete` não importam `org.springframework.*` nem `com.fasterxml.jackson.*`, e não referenciam nenhuma classe de integração.
*Verificação:* `grep -rn "org.springframework\|com.fasterxml" src/main/java/br/com/itau/geradornotafiscal/domain` vazio.

**REQ-3.5 — Portas explícitas de entrada e saída; nenhum `new` de serviço.**
Existe `GerarNotaFiscalUseCase` e as cinco portas de saída como interfaces; os quatro serviços viram adaptadores anotados como beans; tudo é injetado por construtor.
*Verificação:* nenhum `new` de serviço, calculadora ou porta em `src/main/java`.

**REQ-3.6 — Tratamento de erro com contexto.**
Os cinco `throw new RuntimeException(e)` viram `IntegracaoDownstreamException` com integração e `idNotaFiscal`, restaurando `Thread.currentThread().interrupt()`.

**REQ-3.7 — D-03 implementada.**
Regime sem regra definida responde `422` com erro explícito; o teste de caracterização de SPEC-02 é atualizado **no mesmo commit**.

**REQ-3.8 — Contrato preservado e documentado.**
Nenhuma mudança no JSON (P2); `/v3/api-docs` e `/swagger-ui.html` expostos, com nomes de campo idênticos aos `@JsonProperty`.

**REQ-3.9 — Regressão provada sem editar a rede.**
A suíte de SPEC-02 passa **sem nenhuma edição** além do caso de D-03 (P5).

**REQ-3.10 — Evidência de performance versionada.**
Cenários k6 em `k6/`, reexecutáveis como regressão, com os números registrados.

## Desenho

### Estrutura final

```
br.com.itau.geradornotafiscal
├── GeradorNotaFiscalApplication
├── model/                                   (inalterado — contrato JSON congelado)
├── domain/
│   ├── aliquota/
│   │   ├── AliquotaPolicy                   (interface)
│   │   ├── AliquotaPessoaFisicaPolicy
│   │   ├── AliquotaSimplesNacionalPolicy
│   │   ├── AliquotaLucroRealPolicy
│   │   ├── AliquotaLucroPresumidoPolicy
│   │   ├── CalculadoraAliquota              (resolve a policy e delega)
│   │   ├── CalculadoraTributoItem           (ex-CalculadoraAliquotaProduto, stateless)
│   │   └── RegimeTributacaoNaoSuportadoException
│   └── frete/CalculadoraFrete
├── application/
│   ├── GerarNotaFiscalService               (@Service — implementa o use case)
│   ├── config/{DomainConfig, ExecutorConfig}
│   └── port/
│       ├── in/GerarNotaFiscalUseCase
│       └── out/
│           ├── EstoqueNotificacaoPort
│           ├── RegistroNotificacaoPort
│           ├── EntregaNotificacaoPort
│           ├── EntregaIntegrationPort       (interface, agora de verdade)
│           ├── FinanceiroNotificacaoPort
│           └── IntegracaoDownstreamException
└── adapter/
    ├── in/web/{GeradorNFController, ApiExceptionHandler}
    └── out/
        ├── estoque/EstoqueAdapter
        ├── registro/RegistroAdapter
        ├── entrega/{EntregaAdapter, EntregaIntegrationAdapter}
        └── financeiro/FinanceiroAdapter
```

`service/GeradorNotaFiscalService`, `GeradorNotaFiscalServiceImpl`, `service/CalculadoraAliquotaProduto` e os quatro `service/impl/*Service.java` são **removidos** — não coexistem com os novos pacotes.

`model/` permanece como está. Uma hexagonal estrita separaria DTO de transporte e modelo de domínio com mapeadores nos dois sentidos; aqui esses objetos não têm comportamento (são POJOs anêmicos por natureza) e o contrato é congelado — duplicá-los com um mapeador 1:1 sem nenhuma regra de tradução seria indireção sem ganho. REQ-3.4 fala do *domínio de cálculo* não depender de Spring/Jackson, e é isso que a estrutura garante.

### Alíquota como estratégia

Os quatro ramos são quase-duplicados. Extrair para quatro métodos privados resolveria a leitura, mas não o requisito de incluir novas regras sem alterar a classe principal. Como policies resolvidas por uma lista injetada, um quinto regime é uma classe nova registrada — sem tocar nas quatro existentes nem no orquestrador.

```java
// domain/aliquota/AliquotaPolicy.java — plain Java
public interface AliquotaPolicy {
    boolean aplicavelPara(TipoPessoa tipoPessoa, RegimeTributacaoPJ regime);
    double percentualPara(double valorTotalItens);
}
```

```java
// domain/aliquota/AliquotaPessoaFisicaPolicy.java — regra hoje em GeradorNotaFiscalServiceImpl:25-38
public class AliquotaPessoaFisicaPolicy implements AliquotaPolicy {

    @Override
    public boolean aplicavelPara(TipoPessoa tipoPessoa, RegimeTributacaoPJ regime) {
        return tipoPessoa == TipoPessoa.FISICA;
    }

    @Override
    public double percentualPara(double valorTotalItens) {
        if (valorTotalItens < 500)   return 0;
        if (valorTotalItens <= 2000) return 0.12;
        if (valorTotalItens <= 3500) return 0.15;
        return 0.17;
    }
}
```

As outras três seguem o mesmo formato, com `aplicavelPara` checando `JURIDICA && regime == <regime>` e os limiares copiados **sem alteração de valor** de `:48-56`, `:62-70` e `:76-84`.

### Falta de policy é erro, não alíquota zero (D-03)

```java
// domain/aliquota/CalculadoraAliquota.java
public List<ItemNotaFiscal> calcular(Destinatario destinatario, double valorTotalItens, List<Item> itens) {
    AliquotaPolicy policy = policies.stream()
            .filter(p -> p.aplicavelPara(destinatario.getTipoPessoa(), destinatario.getRegimeTributacao()))
            .findFirst()
            .orElseThrow(() -> new RegimeTributacaoNaoSuportadoException(
                    destinatario.getTipoPessoa(), destinatario.getRegimeTributacao()));

    return calculadoraTributoItem.calcular(itens, policy.percentualPara(valorTotalItens));
}
```

O `orElseThrow` é uma escolha deliberada, e é o ponto onde esta spec **altera comportamento observável de propósito**. Um `orElse(0d)` faria `RegimeTributacaoPJ.OUTROS` produzir uma nota com todos os itens tributados a 0% — pior que o comportamento atual e silencioso. O comportamento atual (`itens: []`, sem erro) também é ruim: emite uma nota fiscal sem itens.

Decisão [D-03](../CONSTITUICAO.md#d-03--regimetributacaopjoutros-passa-a-ser-erro-explícito): `OUTROS`, e qualquer combinação sem regra definida, respondem **`422 Unprocessable Entity`**. Num domínio fiscal, recusar é estritamente melhor que emitir algo errado em silêncio. O teste de caracterização de SPEC-02 é atualizado **neste mesmo commit** — é a única edição autorizada naquela suíte.

> `JURIDICA` com `regime_tributacao` nulo é interceptado antes, em SPEC-04, pela validação de payload (`400`). O `422` cobre o que passa pela validação mas não tem regra: hoje, apenas `OUTROS`.

### Calculadora por item — stateless por construção

```java
// domain/aliquota/CalculadoraTributoItem.java
public class CalculadoraTributoItem {

    public List<ItemNotaFiscal> calcular(List<Item> itens, double percentual) {
        List<ItemNotaFiscal> resultado = new ArrayList<>();
        for (Item item : itens) {
            resultado.add(ItemNotaFiscal.builder()
                    .idItem(item.getIdItem())
                    .descricao(item.getDescricao())
                    .valorUnitario(item.getValorUnitario())
                    .quantidade(item.getQuantidade())
                    .valorTributoItem(item.getValorUnitario() * percentual)   // D-05: ignora quantidade, preservado
                    .build());
        }
        return resultado;
    }
}
```

Nenhum campo de classe ou instância. A lista é local ao método e só escapa como retorno — trivialmente thread-safe, e **correta tanto como `new` por chamada quanto como bean singleton**, que é como ela de fato é registrada.

A armadilha a evitar é tratar o bug como sendo o `static`: remover apenas o modificador deixaria um campo de instância que voltaria a vazar assim que o componente virasse singleton via DI. **O problema é o estado, não o modificador.**

A fórmula preserva [D-05](../CONSTITUICAO.md#d-05--valortributoitem-continua-ignorando-quantidade) deliberadamente, com o comentário no ponto exato onde um leitor futuro vai procurar.

### Configuração: domínio fora do Spring

```java
// application/config/DomainConfig.java
@Configuration
public class DomainConfig {

    @Bean
    CalculadoraTributoItem calculadoraTributoItem() {
        return new CalculadoraTributoItem();
    }

    @Bean
    CalculadoraAliquota calculadoraAliquota(CalculadoraTributoItem calculadoraTributoItem) {
        return new CalculadoraAliquota(List.of(
                new AliquotaPessoaFisicaPolicy(),
                new AliquotaSimplesNacionalPolicy(),
                new AliquotaLucroRealPolicy(),
                new AliquotaLucroPresumidoPolicy()),
                calculadoraTributoItem);
    }

    @Bean
    CalculadoraFrete calculadoraFrete() {
        return new CalculadoraFrete();
    }
}
```

O *composition root* fica do lado de `application/`, não no domínio. Se as policies tivessem `@Component`, `domain/` importaria `org.springframework.stereotype` e REQ-3.4 estaria violado na letra. Instanciar com `new` dentro de um `@Bean` custa nada e mantém o domínio plain Java, com o Spring ainda gerenciando o ciclo de vida do agregado.

> O `new` aqui é o do composition root, único ponto autorizado por REQ-3.5 — que proíbe `new` de serviço/porta **dentro** de outra classe de produção, não a construção explícita de beans em `@Configuration`.

### Paralelização com virtual threads (D-01)

```java
// application/config/ExecutorConfig.java
@Configuration
public class ExecutorConfig {

    @Bean(destroyMethod = "close")
    ExecutorService notificacoesExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
```

Virtual threads são adequadas aqui porque as quatro chamadas são I/O bloqueante puro — a carga exata em que superam um pool de plataforma, **sem exigir dimensionamento**. Esse é o ponto: com um pool fixo, cada requisição consome quatro tarefas, então um pool pequeno enfileira sob concorrência e a latência volta a somar, enquanto um pool grande desperdiça threads bloqueadas.

Descartado `StructuredTaskScope`: continua *preview*, e `allOf` sobre virtual threads entrega o mesmo ganho com API estável. Descartado `spring.threads.virtual.enabled=true` global: mudaria também o pool do Tomcat, alterando o perfil de execução do servidor sem necessidade. Descartado `@Async`: exigiria expor cada chamada como método público retornando `CompletableFuture` em um bean proxiado — mais cerimônia para o mesmo resultado.

#### Falha parcial (D-02)

Com as quatro concorrentes, uma falha não impede que as outras três executem — o que **muda a semântica** em relação ao código atual, onde uma exceção em Estoque abortava as três seguintes. Isso precisa ser uma decisão, não um efeito colateral:

```java
CompletableFuture<?>[] notificacoes = {
        CompletableFuture.runAsync(() -> estoquePort.notificar(notaFiscal), notificacoesExecutor),
        CompletableFuture.runAsync(() -> registroPort.notificar(notaFiscal), notificacoesExecutor),
        CompletableFuture.runAsync(() -> entregaPort.agendar(notaFiscal), notificacoesExecutor),
        CompletableFuture.runAsync(() -> financeiroPort.lancar(notaFiscal), notificacoesExecutor)
};

try {
    CompletableFuture.allOf(notificacoes).join();      // aguarda as 4, sem curto-circuito
} catch (CompletionException e) {
    throw NotificacoesParcialmenteFalharamException.de(notaFiscal, notificacoes);  // agrega TODAS as falhas
}
```

`allOf` só completa depois que as quatro completam, então as quatro sempre rodam; o `catch` inspeciona cada future individualmente em vez de propagar apenas a primeira exceção, preservando as causas originais para log e diagnóstico.

A requisição continua respondendo **`500` se qualquer notificação falhar**, e as quatro continuam sendo aguardadas antes da resposta. Deliberadamente **não** adiantamos o "responde 200 e notifica depois": isso só é seguro com o outbox e a saga do [ADR-0001](../../adr/ADR-0001-notificacoes-assincronas-saga-orquestrada.md). Sem eles, responder 200 com uma notificação perdida seria trocar um erro visível por uma inconsistência silenciosa — pior.

A consequência honesta: nesta entrega, uma falha pode deixar efeitos aplicados em três sistemas e ausente no quarto, com a nota já calculada. É precisamente o problema que a Saga do ADR-0001 resolve, e está registrado como tal em [D-02](../CONSTITUICAO.md#d-02--falha-parcial-nas-notificações-mantém-o-comportamento-observável-atual).

`NotaFiscal` é compartilhada pelas quatro threads apenas para leitura — nada a muta depois do `build()` —, então não há corrida sobre ela.

### Portas de saída e tratamento de erro

Duas portas para Entrega, preservando a distinção que já existe informalmente: `EntregaNotificacaoPort` ("agendar entrega", com o preparo interno de 150ms) e `EntregaIntegrationPort` ("chamar a API externa de agendamento", 200ms). Colapsar as duas obrigaria um único adaptador a simular os dois passos.

```java
// adapter/out/entrega/EntregaIntegrationAdapter.java
@Component
public class EntregaIntegrationAdapter implements EntregaIntegrationPort {

    @Override
    public void criarAgendamento(NotaFiscal notaFiscal) {
        try {
            // Latência fixa da integração externa de agendamento — preservada de propósito.
            // Não depende da quantidade de itens: a penalidade condicional que existia aqui
            // era desproporcional e artificial, sem correspondência com uma chamada real.
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IntegracaoDownstreamException("entrega", notaFiscal.getIdNotaFiscal(), e);
        }
    }
}
```

O `+5s` simplesmente não é escrito. Não há "remoção" — não há passo intermediário em que ele exista no código novo (P1).

`IntegracaoDownstreamException` substitui os cinco `throw new RuntimeException(e)`, com `(String integracao, String idNotaFiscal, Throwable causa)` e o status de interrupção restaurado. Restaurar a interrupção importa: sem isso, um cancelamento cooperativo (timeout, shutdown do executor) fica mascarado — e com virtual threads e um executor fechado no shutdown do contexto, esse caminho passa a ser real.

Os outros três adaptadores seguem o mesmo padrão, sem a camada extra de integração, que só existe para Entrega.

### Caso de uso e adaptador web

```java
// adapter/in/web/GeradorNFController.java
@RestController
@RequestMapping("/api/pedido")
public class GeradorNFController {

    private final GerarNotaFiscalUseCase gerarNotaFiscal;

    public GeradorNFController(GerarNotaFiscalUseCase gerarNotaFiscal) {
        this.gerarNotaFiscal = gerarNotaFiscal;
    }

    @Operation(summary = "Gera a nota fiscal (imposto + frete) para um pedido")
    @PostMapping("/gerarNotaFiscal")
    public ResponseEntity<NotaFiscal> gerarNotaFiscal(@RequestBody Pedido pedido) {
        return ResponseEntity.ok(gerarNotaFiscal.gerarNotaFiscal(pedido));
    }
}
```

Sai a variável morta `mensagem` (`web/controller/GeradorNFController.java:28`, montada e nunca usada) e os comentários de placeholder. Entra `@Valid` em SPEC-04.

Esta spec cria `ApiExceptionHandler` com um único handler — `RegimeTributacaoNaoSuportadoException` → `422`. SPEC-04 estende **a mesma classe** com os handlers de validação. Uma classe, duas specs, nenhum retrabalho (P1).

`springdoc-openapi-starter-webmvc-ui` expõe `/v3/api-docs` e `/swagger-ui.html`. Como os `@JsonProperty` já existem em `model/`, o schema sai em snake_case sem trabalho adicional.

## Plano de execução

- [x] Extrair `domain/aliquota/` — `AliquotaPolicy` + uma policy por tipo de pessoa/regime, com os mesmos limiares e percentuais de hoje.
- [x] Extrair `domain/frete/CalculadoraFrete` — resolução da região de entrega e tabela de percentuais.
- [x] Mover a calculadora por item para `domain/aliquota/CalculadoraTributoItem`, **stateless por construção** (nenhum campo de classe ou instância).
- [x] Definir a porta de entrada `GerarNotaFiscalUseCase` e deixar `GeradorNFController` fino (sem a variável morta `mensagem`, sem lógica).
- [x] Definir as portas de saída (`EstoqueNotificacaoPort`, `RegistroNotificacaoPort`, `EntregaNotificacaoPort`, `EntregaIntegrationPort`, `FinanceiroNotificacaoPort`) e implementar os adaptadores correspondentes como beans.
- [x] Eliminar todo `new` de serviço/calculadora do código de produção; tudo via injeção por construtor.
- [x] Escrever `EntregaIntegrationAdapter` **sem** o `Thread.sleep(5000)` condicionado à quantidade de itens; manter o `Thread.sleep(200)` base.
- [x] Disparar as quatro notificações concorrentemente via `CompletableFuture` sobre um `Executor` de virtual threads (D-01), agregando os resultados das quatro (D-02).
- [x] Substituir os 5 `throw new RuntimeException(e)` por `IntegracaoDownstreamException` com contexto (integração + `idNotaFiscal`) e `Thread.currentThread().interrupt()` restaurado.
- [x] Implementar D-03: regime não suportado → `422` com erro explícito; atualizar o teste de caracterização de SPEC-02 no mesmo commit.
- [x] Expor OpenAPI (`/v3/api-docs`, `/swagger-ui.html`) via springdoc, conferindo que os nomes de campo batem com os `@JsonProperty`.
- [x] Testes unitários de domínio (uma classe por policy + `CalculadoraFrete`), plain Java, sem Spring.
- [x] Teste de orquestração do caso de uso com as portas mockadas.
- [x] Cenários k6 versionados em `k6/`.
- [x] Confirmar que a suíte de SPEC-02 passa **sem nenhuma edição**, exceto o caso de D-03.

## Verificação

1. **Unitários de domínio**, plain Java, sem Spring: uma classe por `AliquotaPolicy` cobrindo suas quatro faixas e fronteiras; `CalculadoraFrete` com as 5 regiões e a ausência de endereço de entrega; `CalculadoraTributoItem` com duas chamadas sucessivas sobre listas diferentes, provando independência.
2. **`CalculadoraAliquota`**: policy resolvida corretamente por combinação; `OUTROS` lança `RegimeTributacaoNaoSuportadoException`.
3. **Caso de uso** com as quatro portas mockadas: as quatro são chamadas, a `NotaFiscal` é montada com os valores do domínio, e a falha de qualquer uma produz a exceção agregada — sem depender de tempo real.
4. **Adaptadores de saída**: latência simulada preservada (380/500/150+200/250ms) e, sob `InterruptedException`, exceção com contexto e interrupção restaurada.
5. **`EntregaIntegrationAdapter` com 6 itens**: tempo de execução compatível com 200ms, não com 5,2s.
6. **Regressão de SPEC-02 sem edição**: toda a suíte de caracterização e contrato passa inalterada, exceto o caso de D-03.
7. **Cenários k6**, versionados em `k6/`, reexecutáveis:
   - **A** — pedidos de 1 item vs. ≥6 itens: `p95` comparável, diferença compatível apenas com o processamento extra de itens, jamais com ~5s.
   - **B** — execuções sucessivas sob carga constante: comparar `p95` do primeiro e do último terço da execução; sem tendência de crescimento.
   - **C** — bloco de notificações: tempo total da ordem da chamada mais lenta (~500ms), não da soma (~1280ms).
   - **D** — carga concorrente (não só requisição única): valida que a paralelização não degrada sob concorrência, cenário em que um pool fixo teria falhado.
   - Limiares por ordem de grandeza e sobre `p95`/`p99`, nunca milissegundos exatos, para não ficarem *flaky* em CI compartilhado.
8. **Greps de conformidade estrutural**: `domain/` sem `org.springframework`/`com.fasterxml`; nenhum `static` mutável em `src/main/java`; nenhum `new` de serviço/porta fora de `@Configuration`.

## Fora de escopo

- Validação de payload, Bean Validation e segurança — SPEC-04.
- Métricas, logs e configuração por perfil — SPEC-05.
- Resilience4j: circuit breaker em volta de um `Thread.sleep` não mede nada, e no desenho-alvo (ADR-0001) as quatro chamadas viram consumo de tópico, com retry e DLQ pertencendo a cada consumidor. Não entra nesta entrega.
- Separar DTO de transporte de modelo de domínio com mapeadores — indireção sem ganho com contrato congelado e POJOs anêmicos.
- Outbox, saga e resposta assíncrona — [ADR-0001](../../adr/ADR-0001-notificacoes-assincronas-saga-orquestrada.md), proposta arquitetural, não código desta entrega.
