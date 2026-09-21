package br.com.itau.geradornotafiscal.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@AllArgsConstructor
@Setter
@Getter
public class Item {
	 @JsonProperty("id_item")
	    @NotBlank
	    private String idItem;

	    @JsonProperty("descricao")
	    @NotBlank
	    private String descricao;

	    @JsonProperty("valor_unitario")
	    @NotNull
	    @PositiveOrZero
	    private Double valorUnitario;

	    @JsonProperty("quantidade")
	    @NotNull
	    @Positive
	    private Integer quantidade;




}
