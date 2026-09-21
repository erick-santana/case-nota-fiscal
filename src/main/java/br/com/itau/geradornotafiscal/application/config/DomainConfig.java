package br.com.itau.geradornotafiscal.application.config;

import br.com.itau.geradornotafiscal.domain.aliquota.AliquotaLucroPresumidoPolicy;
import br.com.itau.geradornotafiscal.domain.aliquota.AliquotaLucroRealPolicy;
import br.com.itau.geradornotafiscal.domain.aliquota.AliquotaPessoaFisicaPolicy;
import br.com.itau.geradornotafiscal.domain.aliquota.AliquotaSimplesNacionalPolicy;
import br.com.itau.geradornotafiscal.domain.aliquota.CalculadoraAliquota;
import br.com.itau.geradornotafiscal.domain.aliquota.CalculadoraTributoItem;
import br.com.itau.geradornotafiscal.domain.frete.CalculadoraFrete;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

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
