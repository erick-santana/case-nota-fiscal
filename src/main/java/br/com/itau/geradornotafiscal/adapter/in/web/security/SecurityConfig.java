package br.com.itau.geradornotafiscal.adapter.in.web.security;

import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.web.SecurityFilterChain;

/**
 * A cadeia protegida é o complemento explícito de local/test, não uma lista positiva de ambientes:
 * um perfil de ambiente novo (ex.: "qa") herda a postura protegida por omissão, em vez de cair na
 * auto-configuração permissiva do Spring Boot (SPEC-04, REQ-4.1). Não fica sem {@code @Profile}
 * porque {@code WebSecurityConfiguration} constrói todas as {@code SecurityFilterChain} de forma
 * elegível no boot — sem isso, local/test quebrariam ao tentar resolver um {@code JwtDecoder} que
 * não existe nesses perfis.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Endpoints do Actuator ficam liberados em qualquer perfil: quem decide quem alcança
     * {@code /actuator/*} é o isolamento de rede da porta de management (SPEC-05, REQ-5.1),
     * não uma credencial de aplicação — exigir JWT do scraper do Prometheus ou do health check
     * do target group obrigaria a distribuir um token para infraestrutura, sem ganho de segurança
     * real numa porta que já não é alcançável de fora.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }

    @Bean
    @Profile("!local & !test")
    SecurityFilterChain jwtSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    /**
     * A auto-configuração padrão do Spring Boot para {@code issuer-uri} resolve a property (e faz
     * descoberta OIDC de rede) na construção do bean — inclusive quando a variável de ambiente não
     * está definida, o que quebraria o boot em qualquer perfil não previsto (REQ-4.1). Este bean
     * substitui essa auto-configuração (que recua via {@code @ConditionalOnMissingBean}): sem
     * {@code OIDC_ISSUER_URI}, devolve um decoder que rejeita todo token, em vez de impedir o boot.
     */
    @Bean
    @Profile("!local & !test")
    JwtDecoder jwtDecoder(@Value("${OIDC_ISSUER_URI:}") String issuerUri) {
        if (issuerUri.isBlank()) {
            return token -> {
                throw new BadJwtException("Nenhum emissor OIDC configurado para este ambiente (OIDC_ISSUER_URI ausente).");
            };
        }
        return JwtDecoders.fromIssuerLocation(issuerUri);
    }

    @Bean
    @Profile({"local", "test"})
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    SecurityFilterChain cadeiaPermissivaLocal(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }
}
