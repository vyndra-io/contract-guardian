import jakarta.persistence.Column;
import jakarta.persistence.Entity;

@Entity
public class PaymentEntity {

    @Column(nullable = false)
    private String currency;

    @Column(name = "amount_cents")
    private Long amount;
}
