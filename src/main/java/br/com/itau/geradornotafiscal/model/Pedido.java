package br.com.itau.geradornotafiscal.model;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.*;

@Builder
@AllArgsConstructor
@Getter
@Setter
@NoArgsConstructor
public class Pedido {
	 @JsonProperty("id_pedido")
	    @NotNull
	    @Positive
	    private Integer idPedido;

	    @JsonProperty("data")
	    @NotNull
	    private LocalDate data;

	    @JsonProperty("valor_total_itens")
	    @NotNull
	    @Positive
	    private Double valorTotalItens;

	    @JsonProperty("valor_frete")
	    @NotNull
	    @PositiveOrZero
	    private Double valorFrete;

	    @JsonProperty("itens")
	    @NotEmpty
	    @Valid
	    @Size(max = 50)
	    private List<Item> itens;

	    @JsonProperty("destinatario")
	    @NotNull
	    @Valid
	    private Destinatario destinatario;

	    @AssertTrue(message = "valor_total_itens diverge da soma de valor_unitario × quantidade dos itens")
	    @JsonIgnore
	    public boolean isValorTotalItensConsistente() {
	        if (valorTotalItens == null || itens == null || itens.isEmpty()) {
	            return true;
	        }
	        double soma = itens.stream()
	                .filter(i -> i.getValorUnitario() != null && i.getQuantidade() != null)
	                .mapToDouble(i -> i.getValorUnitario() * i.getQuantidade())
	                .sum();
	        return Math.abs(valorTotalItens - soma) <= 0.01;
	    }

}
