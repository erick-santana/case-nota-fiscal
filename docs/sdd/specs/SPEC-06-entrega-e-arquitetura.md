# SPEC-06 — Ciclo de entrega e visão arquitetural

| | |
|---|---|
| **Depende de** | SPEC-01 (stack do runner), SPEC-03 (nomes reais de portas/adaptadores), SPEC-05 (health check e perfis) |
| **Habilita** | — última frente; valida o conjunto |
| **Decisões aplicáveis** | consome os perfis canônicos de SPEC-05 |
| **Altera comportamento observável?** | Não |
| **Constituição** | [P4](../CONSTITUICAO.md#p4--executável-localmente-sem-aws-real) |

> **Nota de revisão ([SPEC-07](./SPEC-07-outbox-dynamodb.md))**: a tabela porta→adaptador→AWS abaixo (com `EstoqueNotificacaoPort`/`RegistroNotificacaoPort`/`EntregaNotificacaoPort`/`FinanceiroNotificacaoPort`) descreve o estado entregue até esta spec, já superado — ver a versão atual da mesma tabela em [RFC-0001](../../rfc/RFC-0001-arquitetura-produtiva-aws.md#mapeamento-hexagonal-portas-e-adaptadores--componentes-aws). A afirmação de "ausência de persistência" na seção "Verificação" também não é mais verdadeira.

## Objetivo

Entregar um pipeline de CI funcional e a arquitetura produtiva documentada com trade-offs explícitos, para que mudanças cheguem a produção de forma repetível e para que se entenda como este código se mapeia à infraestrutura real.

## Por que por último

Duas razões, ambas de dependência real e não de preferência:

1. **O CI valida o conjunto.** Um pipeline escrito antes de SPEC-01 rodaria contra a stack antiga; escrito antes de SPEC-05 não teria health check para o smoke test do contêiner, e teria que fazer `grep "Started"` no log — que passa mesmo se a aplicação subir quebrada.
2. **O mapeamento porta→AWS exige nomes reais.** Refletir a arquitetura hexagonal no desenho AWS só é verificável citando classes que existem. Com SPEC-03 entregue, a tabela entra no RFC com os nomes reais em vez de placeholders.

## Contexto verificado

Levantamento na raiz do repositório: **não existe** `.github/workflows/`, `Jenkinsfile`, `.gitlab-ci.yml` nem qualquer pipeline. Nenhum commit é validado automaticamente — o que é agravado pelo próprio README relatar que "parte dos testes existentes encontra-se quebrada ou apresenta comportamento inconsistente". Sem CI, uma regressão futura teria o mesmo destino: silenciosa.

O `spring-boot-maven-plugin` já vem com a seção `<image>` configurada, então `./mvnw spring-boot:build-image` é um goal utilizável sem nenhuma peça adicional, e o wrapper (`./mvnw`) está presente e executável.

## Requisitos

**REQ-6.1 — Pipeline de CI verde em PR e em push para `main`.**
Executa build, testes e build da imagem de contêiner.

**REQ-6.2 — O sinal do pipeline existe de fato.**
Um teste quebrado de propósito faz o job falhar e bloquear; um `arn:aws:` inserido de propósito em properties faz o step de segredo falhar.

**REQ-6.3 — Smoke test do contêiner por readiness HTTP.**
Usa `/actuator/health/readiness` de SPEC-05, não `grep` em log.

**REQ-6.4 — Deployment, rollback, promoção e monitoramento pós-deploy documentados.**
Coerentes com RFC-0001 e com os perfis de SPEC-05.

**REQ-6.5 — RFC-0001 contém o mapeamento porta → adaptador → componente AWS.**
Com os nomes reais implementados em SPEC-03.

**REQ-6.6 — Cobertura arquitetural completa.**
Os onze aspectos exigidos têm seção correspondente em RFC-0001/ADR-0001, com alternativas e trade-offs explícitos.

**REQ-6.7 — Nenhum workflow que dependa de credencial inexistente.**
Nenhuma infraestrutura AWS provisionada (P4); o CD fica documentado, não habilitado.

**REQ-6.8 — Documentos de arquitetura revisados contra o código entregue.**
Nenhuma afirmação descreve como "atual" um estado que esta entrega já mudou.

## Desenho

### Estratégia de build

Wrapper Maven exclusivamente, com **JDK 21 no runner** — o mesmo comando que o desenvolvedor roda localmente, reduzindo o risco de "funciona no CI, não funciona local".

Vale registrar por que isso depende de SPEC-01 concluída. É tentador argumentar que o JDK do runner é independente do `<release>` do compilador e adotar JDK 21 desde já — correto para o compilador, **falso para o annotation processing**: sob o parent `2.6.2` o BOM resolve Lombok `1.18.22`, que não compila em JDK 21, e todos os DTOs dependem de Lombok. Com SPEC-01 concluída antes, JDK 21 é simplesmente a stack do projeto.

Comando único: `./mvnw -B clean verify`. O `-B` tira o ruído de progresso interativo dos logs; `verify` em vez de `test` mantém o pipeline compatível caso um Failsafe entre depois, sem editar o workflow.

### Pipeline (`.github/workflows/ci.yml`)

```yaml
name: CI

on:
  push:        { branches: [main] }
  pull_request: { branches: [main] }

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build-and-test:
    name: Build & test
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21', cache: maven }
      - name: Build e testes
        run: ./mvnw -B clean verify
      - name: Verificar ausência de segredo em properties
        run: |
          ! grep -rEn 'amazonaws\.com|arn:aws:|AKIA[0-9A-Z]{16}' src/main/resources/application*.properties
      - uses: actions/upload-artifact@v4
        if: always()
        with: { name: surefire-reports, path: target/surefire-reports/, retention-days: 7 }

  build-image:
    name: Build da imagem (Paketo buildpacks)
    runs-on: ubuntu-latest
    needs: build-and-test
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21', cache: maven }
      - name: Build da imagem
        run: >
          ./mvnw -B -DskipTests spring-boot:build-image
          -Dspring-boot.build-image.imageName=geradornotafiscal:${{ github.sha }}
      - name: Smoke test — readiness do contêiner
        run: |
          docker run -d --name smoke -p 8080:8080 -p 8081:8081 \
            -e SPRING_PROFILES_ACTIVE=local geradornotafiscal:${{ github.sha }}
          for i in $(seq 1 30); do
            if curl -fsS http://localhost:8081/actuator/health/readiness | grep -q '"UP"'; then
              echo "readiness OK"; docker rm -f smoke; exit 0
            fi
            sleep 2
          done
          echo "Aplicação não ficou pronta no tempo esperado"; docker logs smoke; docker rm -f smoke; exit 1
```

Decisões do workflow:

- **Dois jobs com `needs:`** — a imagem só é construída se os testes passarem, e uma falha de teste não se confunde com uma falha de build de imagem no mesmo "X" vermelho.
- **Smoke test por readiness HTTP, não por `grep` no log.** Um `docker logs | grep "Started"` passa mesmo com a aplicação subindo degradada. Com o Actuator de SPEC-05, o pipeline verifica que ela está de fato pronta para receber tráfego — e valida de quebra a mesma probe que o ALB usará em produção.
- **Verificação de segredo em properties** promovida de "candidata a step de CI" para step real. É barata e fecha o requisito de "sem credenciais hardcoded" de forma executável, não por inspeção.
- **Sem `docker save` como artefato.** Subir a imagem inteira a cada execução, sem um job de push que a consuma, é custo de storage sem leitor.
- **Sem push para ECR.** Exigiria conta AWS, repositório provisionado e OIDC — nada disso existe neste desafio (P4). Incluir o passo produziria um workflow que falha permanentemente, dando falso sinal de "quebrado" no histórico do repositório. A estratégia de CD fica documentada abaixo, em prosa e YAML ilustrativo, não como arquivo executável.
- **`concurrency` com `cancel-in-progress`** — evita acumular execuções redundantes em PRs com commits em sequência.

### CD, deployment e rollback (documentado, não habilitado)

Assumindo a infraestrutura de [RFC-0001](../../rfc/RFC-0001-arquitetura-produtiva-aws.md) (ECS Fargate multi-AZ + ALB + ECR):

**Deployment** — rolling update com o *deployment circuit breaker* nativo do ECS (`deploymentCircuitBreaker: { enable: true, rollback: true }`). O ECS substitui tasks gradualmente respeitando `minimumHealthyPercent` e monitora `/actuator/health/readiness` (SPEC-05) via target group durante o rollout; se as novas tasks não ficarem saudáveis, reverte sozinho para a task definition anterior.

Blue/green via CodeDeploy é tecnicamente superior no corte de tráfego (dois target groups, *bake time* antes do corte), e fica registrado como evolução — não como escolha inicial, porque adiciona um componente operacional sem que o desafio apresente um requisito de "zero requisição servida por versão incorreta" que o justifique.

**Rollback** — cada imagem é tagueada pelo `git sha`, nunca `latest`, então reverter é apontar o serviço para uma revisão anterior de task definition, sem rebuild:

```bash
aws ecs update-service --cluster geradornotafiscal-prod --service geradornotafiscal \
  --task-definition geradornotafiscal:<revisão-anterior>
```

Dois gatilhos: **automático** (circuit breaker do ECS, quando o rollout falha health check) e **manual** (bug funcional silencioso detectado depois de o rollout ter sido considerado saudável — cenário que nenhum health check cobre).

**Promoção por ambiente**, coerente com os perfis de [SPEC-05](./SPEC-05-observabilidade-e-configuracao.md) — mesma imagem imutável promovida, só muda a task definition:

| Ambiente | Perfil | Gatilho | Aprovação |
|---|---|---|---|
| `dev` | `dev` | todo merge em `main` que passar no CI | automática |
| `staging` | `staging` | promoção de imagem já validada em `dev` | automática, mas explícita |
| `prod` | `prod` | promoção de imagem já validada em `staging` | humana (`environment` protegido, *required reviewers*) |

**Monitoramento pós-deploy** — sem duplicar o desenho de observabilidade do RFC-0001, o que o *ciclo de entrega* acrescenta são duas coisas: um **bake time** curto de observação da taxa de erro/latência antes de considerar o deploy confirmado, e um **alarme correlacionando "deploy nos últimos N minutos" com aumento de 5xx/latência p99** — o gatilho mais direto para o rollback manual.

O fragmento de CD (OIDC → ECR → `update-service`) fica documentado no histórico desta spec, deliberadamente **fora** de `.github/workflows/`, para não introduzir um workflow que nunca passa.

### Atualizações no RFC-0001

Esta spec fecha a lacuna restante nos documentos de arquitetura: o reflexo explícito da arquitetura hexagonal no desenho AWS.

#### Tabela de mapeamento porta → adaptador → componente AWS

Com os nomes reais de SPEC-03, não mais especulativos:

| Porta (SPEC-03) | Adaptador entregue | Destino produtivo | Componente AWS |
|---|---|---|---|
| `GerarNotaFiscalUseCase` (entrada) | `GeradorNFController` | inalterado — adaptador web fino | API Gateway → VPC Link → ALB interno → ECS Fargate |
| `EstoqueNotificacaoPort` | `EstoqueAdapter` (380ms) | consumer group na conta do time de Estoque | `notafiscal.emitida.v1` / `estoque-service` |
| `RegistroNotificacaoPort` | `RegistroAdapter` (500ms) | consumer group na conta do time de Registro | `notafiscal.emitida.v1` / `registro-service` |
| `EntregaNotificacaoPort` + `EntregaIntegrationPort` | `EntregaAdapter` + `EntregaIntegrationAdapter` (150+200ms) | consumer group na conta do time de Entrega; a chamada à API externa de agendamento passa a ser interna ao consumidor | `notafiscal.emitida.v1` / `entrega-service` |
| `FinanceiroNotificacaoPort` | `FinanceiroAdapter` (250ms) | consumer group na conta do time de Financeiro | `notafiscal.emitida.v1` / `financeiro-service` |
| `domain/aliquota`, `domain/frete` | in-process, plain Java | inalterado | roda dentro do processo ECS, sem componente próprio |

A leitura importante da tabela — e que só fica evidente com os nomes na mão: **as quatro portas de saída não viram quatro adaptadores Kafka.** Elas convergem para **um único** adaptador de publicação (outbox → `notafiscal.emitida.v1`), e o que hoje é "uma porta por sistema notificado" passa a ser "um consumer group por time", do outro lado do backbone. Esse é o passo 3→4 do caminho de migração incremental do RFC. O ganho das portas é que essa troca não toca `domain/` nem `GerarNotaFiscalService`.

## Plano de execução

- [x] Criar `.github/workflows/ci.yml` com JDK 21, `./mvnw -B clean verify` e job de build de imagem com smoke test. Validado localmente: `./mvnw -B clean verify` (128 testes, BUILD SUCCESS), `./mvnw spring-boot:build-image` e o smoke test de readiness contra o contêiner resultante — todos passam com o conteúdo exato do workflow.
- [x] Usar o health check de SPEC-05 no smoke test do contêiner, em vez de grep no log.
- [x] Incluir o step de verificação de segredo em properties. Validado que o `grep` do step de fato falha (exit não-zero) com um `arn:aws:` inserido de propósito, e passa limpo no estado atual do repositório.
- [x] Documentar estratégia de deployment (rolling update + deployment circuit breaker no ECS), rollback (task definition anterior, imagens imutáveis por `git sha`) e monitoramento pós-deploy. Já redigido acima, em "CD, deployment e rollback".
- [x] Documentar a matriz de promoção por ambiente (`dev`/`staging`/`prod`), coerente com os perfis de SPEC-05. Já redigido acima, em "Promoção por ambiente".
- [x] Acrescentar ao RFC-0001 a tabela **porta → adaptador → componente AWS**, com os nomes reais implementados em SPEC-03. Já presente em RFC-0001 (seção "Mapeamento hexagonal: portas e adaptadores → componentes AWS").
- [x] Revisar RFC-0001/ADR-0001 contra o código entregue — remover qualquer afirmação que descreva o estado pré-entrega como atual. Desvio encontrado e corrigido: o Contexto de RFC-0001 e ADR-0001 ainda descrevia as quatro notificações como síncronas *sequenciais* com `new EstoqueService()` etc. e o bug de +5s como estado "hoje" — mas SPEC-03 já entregou portas hexagonais + paralelização via `CompletableFuture` e removeu o bug. A tabela comparativa de RFC-0001 também rotulava "Atual" a linha errada (sequencial, não paralelo). Reescrito para deixar claro qual é o estado herdado (motivação histórica) e qual é o estado atual entregue; corrigido também `GeradorNotaFiscalServiceImpl` → `GerarNotaFiscalService` (classe renomeada em SPEC-03) e a afirmação de que a API Gateway resolveria "hoje sem nenhuma proteção" (SPEC-04 já entrega autenticação JWT a nível de aplicação; o que falta é proteção de borda, não autenticação).

## Verificação

1. **PR verde**: um PR contra `main` dispara `build-and-test`; com um teste quebrado de propósito, o job falha e bloqueia — o sinal precisa existir de fato, não só estar configurado.
2. **Push em `main`**: `build-image` roda após `build-and-test` (não em paralelo) e a imagem passa o smoke test de readiness.
3. **Step de segredo**: um literal `arn:aws:` inserido de propósito em um `application*.properties` faz o job falhar.
4. **Cache**: segunda execução de `build-and-test` reaproveita o cache Maven, com redução perceptível no tempo de resolução de dependências.
5. **Revisão documental**: nenhuma afirmação de RFC-0001/ADR-0001 descreve como "atual" um estado que esta entrega já mudou (quatro chamadas com `new`, `+5s`; a ausência de persistência de estado permanece verdadeira).
6. **Checklist de cobertura arquitetural**: os onze aspectos exigidos (API Gateway, autenticação/autorização, serviços consumidos, integrações externas, comunicação entre serviços, infraestrutura AWS, rede/segurança, escalabilidade/HA, observabilidade, persistência, resiliência) têm seção correspondente em RFC-0001/ADR-0001, com âncora que resolve.
7. `./mvnw clean verify` continua passando localmente — o pipeline não introduz nada que só funcione em CI.

## Fora de escopo

- Provisionar qualquer recurso AWS, ou adicionar workflow que dependa de credencial inexistente (REQ-6.7).
- Push de imagem para ECR e job de deploy — documentados, não habilitados.
- Blue/green via CodeDeploy — registrado como evolução, não escolha inicial.
- IaC (Terraform/CDK): RFC-0001 é proposta arquitetural (P4).

## Adendo — scan de segurança de dependências e de imagem (Trivy)

Adicionado depois da entrega inicial deste spec, a pedido explícito, como reforço de segurança no próprio `ci.yml` — não altera nenhum REQ-6.x acima.

**Ferramenta escolhida: [Trivy](https://github.com/aquasecurity/trivy) (Aqua Security, Apache-2.0) para os dois scans — SCA (dependências) e imagem.** A primeira tentativa foi OWASP Dependency-Check (`org.owasp:dependency-check-maven:13.0.0`), mas falhou de forma reprodutível em execução local: a versão atual da API do NVD exige `nvdApiKey` mesmo para a atualização básica da base — sem ela, o plugin lança `NvdApiException: Invalid API Key` e o build não completa. Provisionar essa credencial contraria o mesmo princípio de REQ-6.7 (nenhum workflow dependendo de credencial que este projeto não tem e não vai criar). Trivy usa sua própria base de vulnerabilidades (agregando GHSA/OSV/etc., distribuída via GHCR), sem exigir nenhuma chave — testado e confirmado localmente antes de entrar no workflow.

**Achado real, corrigido no mesmo commit**: o primeiro scan (`trivy fs` contra o `pom.xml` efetivo) encontrou 3 CVEs **CRITICAL** em `org.apache.tomcat.embed:tomcat-embed-core:11.0.24` (versão trazida pelo BOM do `spring-boot-starter-parent:4.1.1`): CVE-2026-65182, CVE-2026-65905, CVE-2026-68525, todas corrigidas em 11.0.25. Resolvido sobrepondo a property gerenciada pelo BOM (`<tomcat.version>11.0.25</tomcat.version>` em `pom.xml`) — confirmado localmente: os 128 testes continuam verdes e o scan (fs e, depois de reconstruída a imagem, também de imagem) fica limpo.

**Onde entram no `ci.yml`**:
- `build-and-test`, depois de `./mvnw clean verify` (para reaproveitar o `~/.m2` já resolvido): `trivy fs` contra a raiz do repositório — CVEs em dependências diretas/transitivas via `pom.xml`.
- `build-image`, depois de `spring-boot:build-image` e antes do smoke test: `trivy image` contra `geradornotafiscal:${{ github.sha }}` — CVEs no JRE, nas camadas dos buildpacks e nos jars empacotados.
- Ambos com `--severity CRITICAL,HIGH --ignore-unfixed --exit-code 1`: falha o job só para achados com severidade alta que já têm correção disponível — uma CVE sem fix publicado bloquearia o pipeline permanentemente por um problema fora do controle deste repositório.

**Decisão deliberada: não usar a Action `aquasecurity/trivy-action`.** Em março de 2026, esse repositório sofreu um comprometimento de cadeia de suprimentos real: um atacante reescreveu 76 das 77 tags de versão (incluindo tags antigas já usadas em pipelines de terceiros) para apontar para commits maliciosos que roubavam segredos de CI/CD (ver [aviso da Aqua Security](https://github.com/aquasecurity/trivy/security/advisories/GHSA-69fq-xp46-6x23)). Em vez de fixar a Action por SHA (mitigação padrão, mas ainda dependente de uma terceira parte cujo histórico já teve esse problema), o scan invoca diretamente a imagem oficial `aquasec/trivy`, fixada por **digest completo** (`aquasec/trivy:0.74.0@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969`), via `docker run` simples — o runner do GitHub Actions já tem Docker disponível, sem dependência adicional de Action de terceiro para esta etapa.

**Cache**: base de vulnerabilidades do Trivy (~115MB) e base de bibliotecas Java para scan de imagem (~1GB, baixada só na primeira execução da semana) ficam em `~/.cache/trivy`, cacheados via `actions/cache` com chave semanal (`trivy-db-<ano>-<semana>` + `restore-keys` para reaproveitar cache de semanas anteriores) — evita rebaixar ~1GB em toda execução.

**Fora deste adendo, por pedido explícito**: scan de segredos no histórico do git (Gitleaks) e gates de qualidade estática (SpotBugs/PMD/Checkstyle) não foram adicionados — ficam como possível evolução futura.
