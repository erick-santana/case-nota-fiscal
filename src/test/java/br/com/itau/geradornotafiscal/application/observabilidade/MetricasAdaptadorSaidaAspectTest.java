package br.com.itau.geradornotafiscal.application.observabilidade;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Isola a lógica de tagueamento do aspecto (REQ-5.4) de qualquer proxy real do Spring AOP:
 * um {@link ProceedingJoinPoint} mockado é suficiente para provar sucesso/falha por adaptador.
 */
class MetricasAdaptadorSaidaAspectTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MetricasAdaptadorSaidaAspect aspect = new MetricasAdaptadorSaidaAspect(registry);

    @Test
    void chamadaComSucessoRegistraTempoComResultadoSucesso() throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getTarget()).thenReturn(new FakeAdapter());
        when(pjp.proceed()).thenReturn("ok");

        Object resultado = aspect.medir(pjp);

        assertThat(resultado).isEqualTo("ok");
        assertThat(registry.get("integracao_downstream_seconds")
                .tag("adapter", "FakeAdapter")
                .tag("resultado", "sucesso")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void chamadaComFalhaRegistraTempoComResultadoFalhaERelancaAExcecao() throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getTarget()).thenReturn(new FakeAdapter());
        RuntimeException falha = new RuntimeException("falha simulada");
        when(pjp.proceed()).thenThrow(falha);

        assertThatThrownBy(() -> aspect.medir(pjp)).isSameAs(falha);

        assertThat(registry.get("integracao_downstream_seconds")
                .tag("adapter", "FakeAdapter")
                .tag("resultado", "falha")
                .timer().count()).isEqualTo(1);
    }

    private static class FakeAdapter {
    }
}
