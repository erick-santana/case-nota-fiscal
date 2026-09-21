package br.com.itau.geradornotafiscal.application.observabilidade;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Único ponto que instrumenta tempo e falha de todas as portas de saída (REQ-5.4): sem ele,
 * a mesma medição precisaria ser copiada em cada adaptador de {@code adapter/out/}.
 */
@Aspect
@Component
public class MetricasAdaptadorSaidaAspect {

    private final MeterRegistry registry;

    public MetricasAdaptadorSaidaAspect(MeterRegistry registry) {
        this.registry = registry;
    }

    @Around("within(br.com.itau.geradornotafiscal.adapter.out..*)")
    public Object medir(ProceedingJoinPoint pjp) throws Throwable {
        Timer.Sample amostra = Timer.start(registry);
        String adapter = pjp.getTarget().getClass().getSimpleName();
        try {
            Object resultado = pjp.proceed();
            amostra.stop(registry.timer("integracao_downstream_seconds", "adapter", adapter, "resultado", "sucesso"));
            return resultado;
        } catch (Throwable t) {
            amostra.stop(registry.timer("integracao_downstream_seconds", "adapter", adapter, "resultado", "falha"));
            throw t;
        }
    }
}
